package coffee.crema.ui.components

import androidx.compose.runtime.Composable
import coffee.crema.beans.BAG_FORM_FIELD_LABELS
import coffee.crema.beans.CLASH_TITLE
import coffee.crema.beans.CatalogueClashGate
import coffee.crema.beans.ClashChoice
import coffee.crema.beans.clashMessage
import coffee.crema.core.CatalogueField

/**
 * The catalogue clash prompt (web `choiceDialog` parity) for whichever pick
 * [gate] holds: "Some fields are already filled in" — the clashing field
 * names — with **Keep mine** (the tinted confirm: fill empty fields only) and
 * **Use catalogue** (the neutral second button: replace them too). Back / tap
 * outside dismisses: nothing is applied. Same [CremaConfirmDialog] chrome as
 * every other confirm, on phone and tablet.
 */
@Composable
fun CatalogueClashDialog(gate: CatalogueClashGate, labels: Map<CatalogueField, String> = BAG_FORM_FIELD_LABELS) {
    val pending = gate.pending ?: return
    CremaConfirmDialog(
        title = CLASH_TITLE,
        body = clashMessage(pending.clashes, labels),
        confirmLabel = "Keep mine",
        cancelLabel = "Use catalogue",
        onConfirm = { gate.resolve(ClashChoice.KeepMine) },
        onCancel = { gate.resolve(ClashChoice.UseCatalogue) },
        onDismiss = { gate.resolve(null) },
    )
}
