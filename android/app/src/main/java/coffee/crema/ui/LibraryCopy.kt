package coffee.crema.ui

import coffee.crema.core.Bean

/*
 * The words of library confirms (#488 batch 7), kept pure so the tablet and
 * phone screens say the same thing and the tests pin them.
 */

/** The bags (not deleted) that prefer profile [profileId]: a profile delete clears their link. */
internal fun List<Bean>.preferringProfileCount(profileId: String): Int =
    count { it.deletedAt == null && it.linkedProfileId == profileId }

/**
 * The profile delete confirm's body (bean-profile-pin "Deletion cascade",
 * REVIEW-FINAL-2 N3): the profile goes, and the [linkedBags] bags that
 * prefer it lose that link, which the delete can't undo.
 */
internal fun profileDeleteBody(name: String, linkedBags: Int): String {
    val links = when {
        linkedBags <= 0 -> ""
        linkedBags == 1 -> " 1 bag prefers it; that link will be cleared."
        else -> " $linkedBags bags prefer it; their links will be cleared."
    }
    return "“$name” will be removed.$links This can’t be undone."
}
