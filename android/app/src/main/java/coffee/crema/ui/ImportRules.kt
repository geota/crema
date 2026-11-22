package coffee.crema.ui

/*
 * The two import rules, said where each applies (REVIEW-FINAL-2 N8, the
 * closing check's PLAN 62; web `#lib/bean/import-rules.ts`, same words): a
 * backup's Merge only adds (`de1_domain::backup_plan`), while a library or
 * Beanconqueror import replaces a bag you have when the file's copy is newer
 * (`de1_domain::bean_import`). The same file can update a bag or not
 * depending on the menu, so each menu says which.
 */

/** Settings → Restore — merge (and Drive's Merge). */
const val MERGE_RULE =
    "Adds only what you don't have yet; bags already here stay as they are, even when the backup's copy is newer."

/** Beans → Import (a Crema library file or a Beanconqueror export). */
const val LIBRARY_IMPORT_RULE =
    "A bag you already have is replaced when the file's copy is newer. (Restoring a backup with Merge only adds.)"

/** The Beans → Import dialog's body, before the file picker: what it reads, then [LIBRARY_IMPORT_RULE]. */
const val BEANS_IMPORT_BODY =
    "A Crema beans file (.crema.jsonl or .crema.zip) or a Beanconqueror export (.json or .zip). $LIBRARY_IMPORT_RULE"
