package io.github.nath_goa.usugami.ui

import android.os.Parcelable
import io.github.nath_goa.usugami.core.PageLook
import kotlinx.parcelize.Parcelize

/** The two sides of an ID card, front first: they go on its page in this order. */
enum class CardSide { FRONT, BACK }

/**
 * An ID card being scanned: [card] is its number (ImageItem.card), [side] the side wanted next
 * (BACK once the card has an image), [look] the look its next side starts with. Kept in the saved
 * state while the flow goes on (see MainViewModel.card).
 */
@Parcelize
data class CardScan(val card: Long, val side: CardSide = CardSide.FRONT, val look: PageLook = PageLook.ORIGINAL) : Parcelable
