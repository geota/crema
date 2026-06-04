# Recomposition + state hygiene (compose-expert review, P2/P3)

Status: ready-for-agent

From the compose-expert review (state-management + performance):

- **Stop passing the whole `MainUiState` to children.** `BrewScreen` passes the
  unstable `ui` to `LimitsCard(ui=ui)`, `ChannelsRow(ui=ui)`, `BrewFoot(ui=ui)`
  (BrewScreen.kt:132/142/161). `MainUiState` holds `List<…>` fields → unstable →
  those children are **not skippable** and recompose ~25 Hz during a shot even for
  unrelated field changes. Pass only the fields each child reads (or `@Immutable`
  slices). Highest recomposition win.

- **State slicing (architectural).** The flat `MainUiState` + wholesale
  `collectAsState`/`collectAsStateWithLifecycle` means each screen body re-runs on
  any field change. Lift the `CoreOutput` funnel into a `CremaCoreClient` and split
  the snapshot into smaller `@Immutable` slices / `derivedStateOf` sub-views
  (the deferred IMPLEMENTATION-PLAN tasks 6/7). Big, do deliberately.

- **Optional: type-safe navigation.** `AppNavHost` uses string routes
  (`composable("brew")`, `nav.navigate("profile-edit")`). Migrate to Nav 2.8
  type-safe `@Serializable` routes so a bad route string fails at compile time.
