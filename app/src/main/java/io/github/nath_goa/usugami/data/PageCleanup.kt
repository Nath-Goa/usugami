package io.github.nath_goa.usugami.data

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNull
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentCatalog
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Pages taken out of a document's page tree can still be reached through bookmarks, links, named
 * destinations, form fields and the tags of an accessible PDF, and PDFBox writes everything that
 * can be reached. A deleted page, with its text and pictures, would then still be inside the saved
 * file. This cuts those links, so a deleted page is really gone. Pictures only deleted pages drew
 * are emptied, as pages that stay may still list them (pages often share one list of resources),
 * and the tags of what was on deleted pages go.
 */
internal object PageCleanup {
    /**
     * Cuts every link to [removed] pages that are no longer in [doc]'s page tree: their form fields
     * go, bookmarks to them go (unless they hold bookmarks to pages that stay), so do their tags and
     * the pictures and forms only they drew, and any other reference to them or their annotations,
     * such as a link's destination, is emptied. [check] is called now and then.
     */
    fun dropRemovedPages(doc: PDDocument, removed: Collection<PDPage>, check: () -> Unit = {}) {
        val (gone, goneAnnotations) = goneObjects(doc, removed) ?: return
        pruneForm(doc, goneAnnotations)
        pruneOutline(doc, gone, check)
        // Pictures they alone drew go too, though pages that stay may still list them.
        val drawn = identitySet<COSBase>()
        for (page in removed) {
            if (page.cosObject !in gone) continue
            check()
            XObjectCleanup.drawnBy(page, drawn)
        }
        XObjectCleanup.emptyUnused(doc, drawn, doc.pages, check)
        val goneTags = pruneTags(doc, gone, goneAnnotations)
        val cut = identitySet<COSDictionary>().apply { addAll(gone); addAll(goneAnnotations); addAll(goneTags) }
        cutReferences(doc.document.trailer, { it in cut }, check)
    }

    /**
     * The pages of [doc], a document still open that pages are copied from, and every annotation on
     * them: read once, however many documents [dropSourcePages] then cuts off from them.
     */
    class Source(doc: PDDocument, pages: Iterable<PDPage>) {
        internal val pages = identitySet<COSDictionary>()
        internal val annotations = identitySet<COSDictionary>()

        /** Where [keepLinksWithin] finds the page a link to a named place leads to. */
        internal val catalog: PDDocumentCatalog = doc.documentCatalog

        /**
         * Where each link on the pages leads that gives its page rather than a name: a copy of its
         * destination, made before any document is cut off from the pages. Cutting one off empties
         * the destination itself, which a later part of a split that has the page too still needs.
         */
        internal val places = IdentityHashMap<COSDictionary, COSArray>()

        init {
            for (page in pages) {
                this.pages += page.cosObject
                annotationsOf(page.cosObject, annotations)
            }
            for (annotation in annotations) {
                val place = (destinationOf(annotation) as? PDPageDestination)?.cosObject ?: continue
                places[annotation] = COSArray().apply { addAll(place) }
            }
        }
    }

    /**
     * For pages copied into [doc] from [source]'s pages: each link on them to one of those pages gets
     * a copy of its own that leads to that page's copy ([copies], by the page) instead, so a contents
     * page still works in a part of a split. Links to [source]'s other pages are left for
     * [dropSourcePages] to cut. A link to a named place leads straight to its page, since the names
     * don't come along.
     */
    fun keepLinksWithin(doc: PDDocument, source: Source, copies: Map<COSDictionary, COSDictionary>) {
        for (page in doc.pages) {
            val annotations = page.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray ?: continue
            val own = COSArray()
            var changed = false
            for (i in 0 until annotations.size()) {
                val link = (annotations.getObject(i) as? COSDictionary)?.let { linkWithin(it, source, copies) }
                if (link != null) {
                    link.setItem(COSName.P, page.cosObject)
                    changed = true
                }
                // The others as they are, a reference staying a reference.
                own.add(link ?: annotations.get(i))
            }
            if (changed) page.cosObject.setItem(COSName.ANNOTS, own)
        }
    }

    /**
     * A copy of [annotation] leading to the copy of the page it leads to, if it is a link to a page
     * of [source] that has one in [copies]; null otherwise.
     */
    private fun linkWithin(annotation: COSDictionary, source: Source, copies: Map<COSDictionary, COSDictionary>): COSDictionary? {
        val place = source.places[annotation] ?: namedPlace(annotation, source.catalog) ?: return null
        val copy = copies[place.getObject(0)] ?: return null
        val leads = COSArray().apply {
            add(copy)
            for (i in 1 until place.size()) add(place.get(i))
        }
        return COSDictionary(annotation).apply {
            removeItem(COSName.A)
            setItem(COSName.DEST, leads)
        }
    }

    /** Where [annotation] leads, if it is a link to a named place that [catalog] finds; null otherwise. */
    private fun namedPlace(annotation: COSDictionary, catalog: PDDocumentCatalog): COSArray? {
        val name = destinationOf(annotation) as? PDNamedDestination ?: return null
        return try {
            catalog.findNamedDestinationPage(name)?.cosObject
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            // A damaged entry among the names, which PDFBox reads all at once.
            null
        }
    }

    /** Where [annotation] leads, if it is a link that says so readably, itself or by its action. */
    private fun destinationOf(annotation: COSDictionary): PDDestination? {
        if (annotation.getCOSName(COSName.SUBTYPE)?.name != PDAnnotationLink.SUB_TYPE) return null
        return try {
            val link = PDAnnotationLink(annotation)
            link.destination ?: (link.action as? PDActionGoTo)?.destination
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            // A link damaged in another way: a split never fails on one.
            null
        }
    }

    /**
     * For pages copied into [doc] from [source]'s pages: cuts every link from them to those pages
     * and their other annotations, such as a link's destination or a form field's other widgets,
     * and to the form fields of other pages that hang under the same fields as theirs (in forms
     * whose fields nest, as in topmostSubform[0].Page1[0]). Those would bring the source's other
     * pages, or their form values, into [doc]'s file. Nothing else is changed: the copies share
     * their annotations and fields with the source, which loses only links to its own pages and to
     * other pages' fields, which no copy can use anyway.
     */
    fun dropSourcePages(doc: PDDocument, source: Source, check: () -> Unit = {}) {
        val kept = identitySet<COSDictionary>().apply { doc.pages.forEach { annotationsOf(it.cosObject, this) } }
        val otherFields = fieldsElsewhere(kept)
        cutReferences(doc.document.trailer, { it in source.pages || (it in source.annotations && it !in kept) || it in otherFields }, check)
    }

    /**
     * The form fields and widgets that hang under the same fields as the widgets in [kept] but hold
     * none of them, with all they hold: in a form whose fields nest, the fields of other pages.
     */
    private fun fieldsElsewhere(kept: Set<COSDictionary>): Set<COSDictionary> {
        // The fields the widgets kept belong to, up to the top.
        val owners = identitySet<COSDictionary>()
        for (annotation in kept) {
            if (annotation.getCOSName(COSName.SUBTYPE) != COSName.WIDGET) continue
            var field = annotation.getDictionaryObject(COSName.PARENT) as? COSDictionary
            var depth = 0
            while (field != null && depth++ <= MAX_DEPTH && owners.add(field)) field = field.getDictionaryObject(COSName.PARENT) as? COSDictionary
        }
        val others = identitySet<COSDictionary>()
        val pending = ArrayDeque<Pair<COSDictionary, Int>>()
        for (owner in owners) pending += owner to 0
        while (pending.isNotEmpty()) {
            val (field, depth) = pending.removeLast()
            if (depth > MAX_DEPTH) continue
            val kids = field.getDictionaryObject(COSName.KIDS) as? COSArray ?: continue
            for (i in 0 until kids.size()) {
                val kid = kids.getObject(i) as? COSDictionary ?: continue
                if (kid !in owners && kid !in kept && others.add(kid)) pending += kid to depth + 1
            }
        }
        return others
    }

    /**
     * The [removed] pages no longer in [doc]'s page tree, and their annotations that no page left
     * shows; null when no page has gone.
     */
    private fun goneObjects(doc: PDDocument, removed: Collection<PDPage>): Pair<Set<COSDictionary>, Set<COSDictionary>>? {
        val kept = identitySet<COSDictionary>().apply { doc.pages.forEach { add(it.cosObject) } }
        val gone = identitySet<COSDictionary>().apply { removed.forEach { if (it.cosObject !in kept) add(it.cosObject) } }
        if (gone.isEmpty()) return null
        // Their annotations go too (unless a page that stays shows them as well): a reply to a
        // comment, or the tags of an accessible PDF, can still point at them.
        val keptAnnotations = identitySet<COSDictionary>().apply { kept.forEach { annotationsOf(it, this) } }
        val goneAnnotations = identitySet<COSDictionary>().apply { gone.forEach { annotationsOf(it, this) } }
        goneAnnotations.removeAll(keptAnnotations)
        return gone to goneAnnotations
    }

    private fun annotationsOf(page: COSDictionary, into: MutableSet<COSDictionary>) {
        val annotations = page.getDictionaryObject(COSName.ANNOTS) as? COSArray ?: return
        for (i in 0 until annotations.size()) (annotations.getObject(i) as? COSDictionary)?.let(into::add)
    }

    /** Form fields whose widgets were all on removed pages go, so their values don't stay behind. */
    private fun pruneForm(doc: PDDocument, widgets: Set<COSDictionary>) {
        val form = doc.documentCatalog.cosObject.getDictionaryObject(COSName.ACRO_FORM) as? COSDictionary ?: return
        val fields = form.getDictionaryObject(COSName.FIELDS) as? COSArray ?: return
        if (widgets.isNotEmpty()) pruneFields(fields, widgets, 0)
        // The calculation order lists fields too; one that has gone mustn't stay in it.
        val order = form.getDictionaryObject(COSName.CO) as? COSArray ?: return
        val remaining = identitySet<COSDictionary>().also { collectFields(fields, it, 0) }
        for (i in order.size() - 1 downTo 0) {
            if (order.getObject(i) !in remaining) order.remove(i)
        }
    }

    private fun pruneFields(kids: COSArray, widgets: Set<COSDictionary>, depth: Int) {
        if (depth > MAX_DEPTH) return
        for (i in kids.size() - 1 downTo 0) {
            val kid = kids.getObject(i) as? COSDictionary ?: continue
            // A widget on a removed page, or a field that is its own widget there.
            if (kid in widgets) {
                kids.remove(i)
                continue
            }
            val grandKids = kid.getDictionaryObject(COSName.KIDS) as? COSArray ?: continue
            if (grandKids.size() == 0) continue
            pruneFields(grandKids, widgets, depth + 1)
            // A field left with no widgets and no fields under it has gone with its pages.
            if (grandKids.size() == 0) kids.remove(i)
        }
    }

    private fun collectFields(kids: COSArray, into: MutableSet<COSDictionary>, depth: Int) {
        if (depth > MAX_DEPTH) return
        for (i in 0 until kids.size()) {
            val kid = kids.getObject(i) as? COSDictionary ?: continue
            if (!into.add(kid)) continue
            (kid.getDictionaryObject(COSName.KIDS) as? COSArray)?.let { collectFields(it, into, depth + 1) }
        }
    }

    /**
     * Bookmarks that lead only to removed pages go; their /Count is then put right. Each bookmark is
     * read once: in a damaged outline, the bookmarks under one can lead back to those above it.
     */
    private fun pruneOutline(doc: PDDocument, gone: Set<COSDictionary>, check: () -> Unit) {
        val root = doc.documentCatalog.cosObject.getDictionaryObject(COSName.OUTLINES) as? COSDictionary ?: return
        pruneBookmarks(doc, root, gone, identitySet<COSDictionary>().apply { add(root) }, check, 0)
        recount(root, isRoot = true, identitySet<COSDictionary>().apply { add(root) }, depth = 0)
    }

    private fun pruneBookmarks(doc: PDDocument, parent: COSDictionary, gone: Set<COSDictionary>, seen: MutableSet<COSDictionary>, check: () -> Unit, depth: Int) {
        if (depth > MAX_DEPTH) return
        var item = parent.getDictionaryObject(COSName.FIRST) as? COSDictionary
        while (item != null && seen.add(item)) {
            check()
            val next = item.getDictionaryObject(COSName.NEXT) as? COSDictionary
            pruneBookmarks(doc, item, gone, seen, check, depth + 1)
            val target = try {
                PDOutlineItem(item).findDestinationPage(doc)?.cosObject
            } catch (_: IOException) {
                null
            } catch (_: RuntimeException) {
                null
            }
            if (target != null && target in gone && item.getDictionaryObject(COSName.FIRST) == null) unlink(parent, item)
            item = next
        }
    }

    /** Takes [item] out of [parent]'s list of bookmarks. Raw items are moved, so references stay references. */
    private fun unlink(parent: COSDictionary, item: COSDictionary) {
        val prev = item.getItem(COSName.PREV)
        val next = item.getItem(COSName.NEXT)
        val prevItem = item.getDictionaryObject(COSName.PREV) as? COSDictionary
        val nextItem = item.getDictionaryObject(COSName.NEXT) as? COSDictionary
        if (prevItem != null) prevItem.setOrRemove(COSName.NEXT, next) else parent.setOrRemove(COSName.FIRST, next)
        if (nextItem != null) nextItem.setOrRemove(COSName.PREV, prev) else parent.setOrRemove(COSName.LAST, prev)
    }

    private fun COSDictionary.setOrRemove(key: COSName, value: COSBase?) {
        if (value == null || value is COSNull) removeItem(key) else setItem(key, value)
    }

    /**
     * Sets [node]'s /Count from its remaining bookmarks: an open one counts those shown under it, a
     * closed one the negative of that; the root counts every bookmark shown.
     */
    private fun recount(node: COSDictionary, isRoot: Boolean, seen: MutableSet<COSDictionary>, depth: Int) {
        var shown = 0
        var item = node.getDictionaryObject(COSName.FIRST) as? COSDictionary
        while (item != null && depth <= MAX_DEPTH && seen.add(item)) {
            recount(item, isRoot = false, seen, depth = depth + 1)
            shown += 1 + maxOf(0, item.getInt(COSName.COUNT, 0))
            item = item.getDictionaryObject(COSName.NEXT) as? COSDictionary
        }
        when {
            isRoot -> node.setInt(COSName.COUNT, shown)
            shown == 0 -> node.removeItem(COSName.COUNT)
            else -> node.setInt(COSName.COUNT, if (node.getInt(COSName.COUNT, 0) > 0) shown else -shown)
        }
    }

    /**
     * Takes out of the tags (the structure tree of an accessible PDF) the elements whose content was
     * all on [gone] pages or in [goneAnnotations], with their alternate text, ActualText, titles and
     * expansions, which can repeat what the pages said. Returns them, so that references to them from
     * elsewhere (the parent tree) are cut too. Elements with content on pages that stay are kept.
     */
    private fun pruneTags(doc: PDDocument, gone: Set<COSDictionary>, goneAnnotations: Set<COSDictionary>): Set<COSDictionary> {
        val root = doc.documentCatalog.cosObject.getDictionaryObject(COSName.STRUCT_TREE_ROOT) as? COSDictionary ?: return emptySet()
        val dropped = identitySet<COSDictionary>()
        val seen = identitySet<COSDictionary>()

        /**
         * Removes [element]'s kids that were on gone pages: marked content (by number, or by a
         * reference saying which page), objects such as annotations, and elements left with nothing.
         * Returns whether [element] goes too: it had content there and has none left. Its content is
         * on [page] unless it or an item says otherwise.
         */
        fun prune(element: COSDictionary, page: COSBase?, depth: Int): Boolean {
            if (depth > MAX_DEPTH || !seen.add(element)) return false
            val on = element.getDictionaryObject(COSName.PG) ?: page
            fun goes(kid: COSBase?): Boolean = when {
                kid is COSInteger -> on in gone
                kid !is COSDictionary -> false
                kid.getCOSName(COSName.TYPE) == MARKED_CONTENT || kid.getCOSName(COSName.TYPE) == OBJECT ->
                    (kid.getDictionaryObject(COSName.PG) ?: on) in gone || kid.getDictionaryObject(COSName.OBJ) in goneAnnotations
                else -> prune(kid, on, depth + 1).also { if (it) dropped += kid }
            }
            val isRoot = element === root
            return when (val kids = element.getDictionaryObject(COSName.K)) {
                // An element on a gone page with nothing in it, such as a figure's alternate text alone.
                null -> !isRoot && on in gone
                is COSArray -> {
                    if (kids.size() == 0) return !isRoot && on in gone
                    for (i in kids.size() - 1 downTo 0) if (goes(kids.getObject(i))) kids.remove(i)
                    !isRoot && kids.size() == 0
                }
                else -> goes(kids).also { if (it) element.removeItem(COSName.K) } && !isRoot
            }
        }
        prune(root, null, 0)
        // The index of elements by their /ID, and the parent tree's entries for single elements (an
        // annotation's tag), lose those that went, rather than keep their keys with nothing.
        if (dropped.isNotEmpty()) {
            for ((tree, entries) in listOf(COSName.ID_TREE to COSName.NAMES, COSName.PARENT_TREE to COSName.NUMS)) {
                val node = root.getDictionaryObject(tree) as? COSDictionary ?: continue
                if (pruneEntries(node, entries, dropped, identitySet(), 0)) root.removeItem(tree)
            }
        }
        return dropped
    }

    /**
     * Takes out of the name or number tree at [node] (its pairs listed in [entries]) those whose
     * value is one of [dropped], and puts its limits right. Returns whether [node] is left empty.
     */
    private fun pruneEntries(node: COSDictionary, entries: COSName, dropped: Set<COSDictionary>, seen: MutableSet<COSDictionary>, depth: Int): Boolean {
        if (depth > MAX_DEPTH || !seen.add(node)) return false
        var empty = true
        var first: COSBase? = null
        var last: COSBase? = null
        (node.getDictionaryObject(entries) as? COSArray)?.let { pairs ->
            for (pair in pairs.size() / 2 - 1 downTo 0) {
                if (pairs.getObject(2 * pair + 1) in dropped) {
                    pairs.remove(2 * pair + 1)
                    pairs.remove(2 * pair)
                }
            }
            if (pairs.size() >= 2) {
                empty = false
                first = pairs.getObject(0)
                last = pairs.getObject(pairs.size() / 2 * 2 - 2)
            }
        }
        (node.getDictionaryObject(COSName.KIDS) as? COSArray)?.let { kids ->
            for (i in kids.size() - 1 downTo 0) {
                val kid = kids.getObject(i) as? COSDictionary ?: continue
                if (pruneEntries(kid, entries, dropped, seen, depth + 1)) kids.remove(i)
            }
            if (kids.size() > 0) {
                empty = false
                fun limit(kid: COSBase?, end: Int) = ((kid as? COSDictionary)?.getDictionaryObject(COSName.LIMITS) as? COSArray)?.getObject(end)
                first = first ?: limit(kids.getObject(0), 0)
                last = limit(kids.getObject(kids.size() - 1), 1) ?: last
            }
        }
        // Nodes but the root say which keys they hold.
        if (node.getDictionaryObject(COSName.LIMITS) is COSArray) {
            val low = first
            val high = last
            if (low != null && high != null) node.setItem(COSName.LIMITS, COSArray().apply { add(low); add(high) })
        }
        return empty
    }

    /**
     * Walks everything that can be reached from [start] and empties each reference to what has
     * [gone] (pages, annotations, form fields, tags): a dictionary entry is removed, an array entry
     * becomes null. They aren't entered, so nothing reached only through them is written.
     */
    private fun cutReferences(start: COSBase, gone: (COSDictionary) -> Boolean, check: () -> Unit) {
        val seen = identitySet<COSBase>()
        val pending = ArrayDeque<COSBase>()
        pending.add(start)
        var visited = 0
        while (pending.isNotEmpty()) {
            val current = pending.removeLast()
            if (!seen.add(current)) continue
            if (++visited % 2000 == 0) check()
            when (current) {
                is COSDictionary -> for (key in current.keySet().toList()) {
                    when (val value = current.getDictionaryObject(key)) {
                        is COSDictionary -> if (gone(value)) current.removeItem(key) else pending.add(value)
                        is COSArray -> pending.add(value)
                        else -> Unit
                    }
                }
                is COSArray -> for (i in 0 until current.size()) {
                    when (val value = current.getObject(i)) {
                        is COSDictionary -> if (gone(value)) current.set(i, COSNull.NULL) else pending.add(value)
                        is COSArray -> pending.add(value)
                        else -> Unit
                    }
                }
                else -> Unit
            }
        }
    }

    /** Bookmarks, fields and tags nest; a damaged file could make them loop or nest without end. */
    private const val MAX_DEPTH = 64

    /** The types of a tag's references to marked content and to objects, such as annotations. */
    private val MARKED_CONTENT = COSName.getPDFName("MCR")
    private val OBJECT = COSName.getPDFName("OBJR")

    private fun <T> identitySet(): MutableSet<T> = Collections.newSetFromMap(IdentityHashMap())
}
