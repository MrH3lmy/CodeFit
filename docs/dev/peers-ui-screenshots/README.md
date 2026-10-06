# Peers page screenshots

Rendered from the real `peer.fxml` + `PeerController` with the application's own stylesheets (dark theme)
by `PeerScreenshotHarness` (a manual dev tool in `src/test/java/com/codefit/controller/`; needs a display, e.g.
`xvfb-run`). Cards that would need a live second device (connected peer, comparison, active match) are built
through `PeerCardView` from the same view-models the controller uses and inserted into the real page.
`-560` images are the app's narrowest content width (the window's 760px minimum minus the sidebar).
