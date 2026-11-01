package io.github.nath_goa.usugami.data

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNull
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Pages taken out of a document's page tree can still be reached through bookmarks, links, named
 * destinations, form fields and the tags of an accessible PDF, and PDFBox writes everything that
 * can be reached. A deleted page, with its text and pictures, would then still be inside the saved
 * file. This cuts those links, so a deleted page is really gone.
 */
internal object PageCleanup {
    /**
     * Cuts every link to [removed] pages that are no longer in [doc]'s page tree: their form fields
     * go, bookmarks to them go (unless they hold bookmarks to pages that stay), and any other
     * reference to them or their annotations, such as a link's destination, is emptied. [check] is
     * called now and then.
     */
    fun dropRemovedPages(doc: PDDocument, removed: Collection<PDPage>, check: () -> Unit = {}) {
        val kept = identitySet<COSDictionary>().apply { doc.pages.forEach { add(it.cosObject) } }
        val gone = identitySet<COSDictionary>().apply { removed.forEach { if (it.cosObject !in kept) add(it.cosObject) } }
        if (gone.isEmpty()) return
        // Their annotations go too (unless a page that stays shows them as well): a reply to a
        // comment, or the tags of an accessible PDF, can still point at them.
        val keptAnnotations = identitySet<COSDictionary>().apply { kept.forEach { annotationsOf(it, this) } }
        val goneAnnotations = identitySet<COSDictionary>().apply { gone.forEach { annotationsOf(it, this) } }
        goneAnnotations.removeAll(keptAnnotations)
        pruneForm(doc, goneAnnotations)
        pruneOutline(doc, gone)
        cutReferences(doc.document.trailer, identitySet<COSDictionary>().apply { addAll(gone); addAll(goneAnnotations) }, check)
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

    /** Bookmarks that lead only to removed pages go; their /Count is then put right. */
    private fun pruneOutline(doc: PDDocument, gone: Set<COSDictionary>) {
        val root = doc.documentCatalog.cosObject.getDictionaryObject(COSName.OUTLINES) as? COSDictionary ?: return
        pruneBookmarks(doc, root, gone, 0)
        recount(root, isRoot = true, depth = 0)
    }

    private fun pruneBookmarks(doc: PDDocument, parent: COSDictionary, gone: Set<COSDictionary>, depth: Int) {
        if (depth > MAX_DEPTH) return
        val seen = identitySet<COSDictionary>()
        var item = parent.getDictionaryObject(COSName.FIRST) as? COSDictionary
        while (item != null && seen.add(item)) {
            val next = item.getDictionaryObject(COSName.NEXT) as? COSDictionary
            pruneBookmarks(doc, item, gone, depth + 1)
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
    private fun recount(node: COSDictionary, isRoot: Boolean, depth: Int) {
        var shown = 0
        val seen = identitySet<COSDictionary>()
        var item = node.getDictionaryObject(COSName.FIRST) as? COSDictionary
        while (item != null && seen.add(item) && depth <= MAX_DEPTH) {
            recount(item, isRoot = false, depth = depth + 1)
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
     * Walks everything that can be reached from [start] and empties each reference to [gone] pages
     * and annotations: a dictionary entry is removed, an array entry becomes null. They aren't
     * entered, so nothing reached only through them is written.
     */
    private fun cutReferences(start: COSBase, gone: Set<COSDictionary>, check: () -> Unit) {
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
                        is COSDictionary -> if (value in gone) current.removeItem(key) else pending.add(value)
                        is COSArray -> pending.add(value)
                        else -> Unit
                    }
                }
                is COSArray -> for (i in 0 until current.size()) {
                    when (val value = current.getObject(i)) {
                        is COSDictionary -> if (value in gone) current.set(i, COSNull.NULL) else pending.add(value)
                        is COSArray -> pending.add(value)
                        else -> Unit
                    }
                }
                else -> Unit
            }
        }
    }

    /** Bookmarks and fields nest; a damaged file could make them loop or nest without end. */
    private const val MAX_DEPTH = 64

    private fun <T> identitySet(): MutableSet<T> = Collections.newSetFromMap(IdentityHashMap())
}
