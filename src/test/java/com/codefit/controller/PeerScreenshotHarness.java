package com.codefit.controller;

import com.codefit.config.DatabaseConfig;
import com.codefit.peer.match.MatchRole;
import com.codefit.peer.match.MatchStatus;
import com.codefit.peer.match.StudyMatch;
import com.codefit.peer.protocol.MatchDuration;
import com.codefit.peer.protocol.ObjectId;
import com.codefit.service.ContactService;
import com.codefit.service.IdentityService;
import com.codefit.service.NetworkingService;
import com.codefit.ui.PeerCardPresentation;
import com.codefit.ui.PeerCardView;
import com.codefit.ui.PeerComparisonPresentation;
import com.codefit.ui.PeerConnectionPresenter;
import com.codefit.ui.PeerDialGate;
import com.codefit.ui.StudyMatchPresentation;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.control.ScrollPane;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

/**
 * MANUAL dev tool, not a test (its name deliberately does not match Surefire's patterns): renders the
 * real {@code peer.fxml} + {@code PeerController} - with the application's own stylesheets and theme
 * classes - in representative states and writes one PNG per state, so the Peers screen can be inspected
 * visually without a person driving the app. Needs a display (e.g. {@code xvfb-run}).
 *
 * <p>Peer cards that would need a live second device (connected, active match, comparison data) are
 * built directly through {@link PeerCardView} from the same pure view-models the controller uses and
 * inserted into the real page. Run:
 * {@code xvfb-run -a java -cp target/classes:target/test-classes:<deps> com.codefit.controller.PeerScreenshotHarness <outDir>}.
 */
public final class PeerScreenshotHarness {

    private static final String[] STYLESHEETS = {"/css/tokens.css", "/css/base.css", "/css/controls.css", "/css/shell.css",
            "/css/review.css", "/css/library.css", "/css/forms.css", "/css/progress.css", "/css/today.css",
            "/css/problems.css", "/css/solving-workspace.css"};

    private static Path outDir;

    public static void main(String[] args) throws Exception {
        outDir = Path.of(args.length > 0 ? args[0] : "peer-shots");
        Files.createDirectories(outDir);
        Path db = Files.createTempDirectory("peer-shots-db");
        DatabaseConfig.useDatabaseFile(db.resolve("shots.db"));
        DatabaseConfig.initialize();
        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(started::countDown);
        started.await();
        Platform.setImplicitExit(false);
        Stage[] stage = new Stage[1];
        CountDownLatch created = new CountDownLatch(1);
        Platform.runLater(() -> {
            stage[0] = new Stage();
            created.countDown();
        });
        created.await();
        try {
            new PeerScreenshotHarness().run(stage[0]);
        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            Platform.exit();
            System.exit(0);
        }
    }

    private void run(Stage stage) throws Exception {
        IdentityService identityService = new IdentityService();
        ContactService contactService = new ContactService();
        PeerConnectionPresenter presenter = new PeerConnectionPresenter();
        NetworkingService networkingService = new NetworkingService(presenter::onEvent);

        // 1. First-time: no identity, no peers.
        shoot(stage, "01-first-time", 1000, identityService, networkingService, contactService, presenter, c -> { });

        // 2. Identity exists, networking off.
        identityService.createIdentity("shots-passphrase".toCharArray(), Instant.ofEpochMilli(System.currentTimeMillis()));
        shoot(stage, "02-network-off", 1000, identityService, networkingService, contactService, presenter, c -> { });

        // 3. Online, no peers, add-a-peer open with a real invitation and a parsed (their) invitation.
        Thread.sleep(1_100);
        networkingService.enableNetworking("shots-passphrase".toCharArray(), 0, Instant.ofEpochMilli(System.currentTimeMillis()));
        shoot(stage, "03-online-no-peers", 1000, identityService, networkingService, contactService, presenter, c -> { });
        shoot(stage, "04-add-peer-verify", 1000, identityService, networkingService, contactService, presenter, c -> {
            c.toggleAddPeer();
            c.invitationPassphraseField.setText("shots-passphrase");
            c.createInvitation();
        }, c -> {
            c.peerInvitationArea.setText(c.myInvitationArea.getText());
            c.parseInvitation();
        });

        // 5-9. Peer cards in assorted states, at wide and at the app's narrowest content width.
        for (int width : new int[]{1000, 560}) {
            String suffix = "-" + width;
            shoot(stage, "05-connected-no-comparison" + suffix, width, identityService, networkingService, contactService, presenter,
                    c -> cards(c, card("Ahmed Hassan", FP_A, true, false, Optional.empty())));
            shoot(stage, "06-connected-comparison" + suffix, width, identityService, networkingService, contactService, presenter,
                    c -> {
                        PeerCardView view = card("Ahmed Hassan", FP_A, true, false, Optional.empty());
                        view.showComparison(rows(), Optional.of(Instant.parse("2026-10-06T17:37:00Z")));
                        cards(c, view);
                    });
            shoot(stage, "07-pending-match" + suffix, width, identityService, networkingService, contactService, presenter,
                    c -> cards(c,
                            card("Ahmed Hassan", FP_A, true, false, Optional.of(match(MatchRole.CHALLENGER, MatchStatus.PENDING, 15, 0))),
                            card("Layla", FP_B, true, false, Optional.of(match(MatchRole.OPPONENT, MatchStatus.PENDING, 30, 0)))));
            shoot(stage, "08-active-match" + suffix, width, identityService, networkingService, contactService, presenter,
                    c -> {
                        PeerCardView view = card("Ahmed Hassan", FP_A, true, false, Optional.of(match(MatchRole.CHALLENGER, MatchStatus.ACTIVE, 30, 522)));
                        view.showMatchProgress(rows(), Optional.of(Instant.parse("2026-10-06T17:37:00Z")), Optional.of("You're ahead."));
                        cards(c, view);
                    });
            shoot(stage, "09-multiple-peers" + suffix, width, identityService, networkingService, contactService, presenter,
                    c -> {
                        PeerCardView offline = card("Mohamed Abdelrahman El-Sayed Ibrahim the Third of Alexandria", FP_B, false, false,
                                Optional.of(match(MatchRole.CHALLENGER, MatchStatus.COMPLETED, 60, 0)));
                        offline.setFeedback("Couldn't connect. The other device couldn't be reached. Check that it's online and on the same network.",
                                PeerCardView.FeedbackTone.ERROR);
                        PeerCardView noNet = card("", FP_C, false, false, Optional.empty());
                        cards(c, card("Ahmed Hassan", FP_A, true, false, Optional.of(match(MatchRole.OPPONENT, MatchStatus.DECLINED, 15, 0))),
                                offline, noNet);
                    });
        }
    }

    private static final String FP_A = "d1bb 752a 4f3e 99aa 00bb 11cc 22dd 33ee 44ff 55aa 66bb 77cc 88dd 99ee dacf b664";
    private static final String FP_B = "0a1b 2c3d 4e5f 6071 8293 a4b5 c6d7 e8f9 0123 4567 89ab cdef 0f1e 2d3c 4b5a 6978";
    private static final String FP_C = "ffee ddcc bbaa 9988 7766 5544 3322 1100 0102 0304 0506 0708 090a 0b0c 0d0e 0f10";

    private static PeerCardView card(String name, String fingerprint, boolean connected, boolean dialing, Optional<StudyMatch> match) {
        PeerCardPresentation presentation = PeerCardPresentation.of(name, "", fingerprint, true, connected, dialing, false,
                connected ? Optional.empty() : Optional.of("the other device has not imported your invitation yet"));
        StudyMatchPresentation matchView = StudyMatchPresentation.of(match, connected, presentation.spokenName(), Instant.now());
        return new PeerCardView(presentation, matchView, false, new PeerCardView.Actions() {
            public void connect(PeerCardView card) { }
            public void disconnect(PeerCardView card) { }
            public void compare(PeerCardView card) { }
            public void share(PeerCardView card) { }
            public void startMatch(PeerCardView card, MatchDuration duration) { }
            public void acceptMatch(PeerCardView card) { }
            public void declineMatch(PeerCardView card) { }
            public void cancelMatch(PeerCardView card) { }
            public void refreshMatch(PeerCardView card) { }
            public void matchPickerChanged(PeerCardView card, boolean open) { }
        });
    }

    private static StudyMatch match(MatchRole role, MatchStatus status, int minutes, int remainingSeconds) {
        Instant now = Instant.now();
        boolean timed = status == MatchStatus.ACTIVE || status == MatchStatus.COMPLETED;
        MatchDuration duration = MatchDuration.ofMinutes(minutes);
        Instant ends = status == MatchStatus.ACTIVE ? now.plusSeconds(remainingSeconds) : now.minusSeconds(60);
        return new StudyMatch(new ObjectId(new byte[ObjectId.LENGTH]), 1L, role, duration, status, now, timed ? now : null,
                timed ? ends.minus(Duration.ofMinutes(minutes)) : null, timed ? ends : null, now);
    }

    private static PeerComparisonPresentation.Rendered rows() {
        return new PeerComparisonPresentation.Rendered(PeerComparisonPresentation.Kind.ROWS, List.of(
                new PeerComparisonPresentation.Row("Problems solved", "4", "3"),
                new PeerComparisonPresentation.Row("Problems attempted", "6", "5"),
                new PeerComparisonPresentation.Row("Reviews", "20", "14"),
                new PeerComparisonPresentation.Row("Problem-solving time", "42 min", "31 min"),
                new PeerComparisonPresentation.Row("Review accuracy", "85%", "79%")), null);
    }

    private static void cards(PeerController controller, PeerCardView... cards) {
        controller.emptyPeersBox.setVisible(false);
        controller.emptyPeersBox.setManaged(false);
        controller.contactsBox.getChildren().setAll(cards);
        controller.peersSummaryLabel.setText(cards.length + " paired · " + java.util.Arrays.stream(cards)
                .filter(c -> c.presentation().connection() == PeerCardPresentation.Connection.CONNECTED).count() + " connected");
    }

    private void shoot(Stage stage, String name, int sceneWidth, IdentityService identityService, NetworkingService networkingService,
                       ContactService contactService, PeerConnectionPresenter presenter, Consumer<PeerController> setup) throws Exception {
        shoot(stage, name, sceneWidth, identityService, networkingService, contactService, presenter, setup, c -> { });
    }

    private void shoot(Stage stage, String name, int sceneWidth, IdentityService identityService, NetworkingService networkingService,
                       ContactService contactService, PeerConnectionPresenter presenter, Consumer<PeerController> setup,
                       Consumer<PeerController> afterBackgroundWork) throws Exception {
        CountDownLatch loaded = new CountDownLatch(1);
        PeerController[] holder = new PeerController[1];
        ScrollPane[] scrollHolder = new ScrollPane[1];
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/peer.fxml"));
                loader.setControllerFactory(type -> new PeerController(identityService, networkingService, contactService, presenter, new PeerDialGate()));
                ScrollPane page = loader.load();
                holder[0] = loader.getController();
                scrollHolder[0] = page;
                StackPane root = new StackPane(page);
                root.getStyleClass().addAll("app-root", "theme-dark");
                Scene scene = new Scene(root, sceneWidth, 3200);
                for (String sheet : STYLESHEETS) {
                    scene.getStylesheets().add(getClass().getResource(sheet).toExternalForm());
                }
                stage.setScene(scene);
                stage.show();
            } catch (Throwable t) {
                t.printStackTrace();
            } finally {
                loaded.countDown();
            }
        });
        loaded.await();
        Platform.runLater(() -> setup.accept(holder[0]));
        sleep(2500);
        Platform.runLater(() -> afterBackgroundWork.accept(holder[0]));
        sleep(1500);

        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                Node content = scrollHolder[0].getContent();
                scrollHolder[0].applyCss();
                scrollHolder[0].layout();
                double height = Math.min(3000, Math.max(300, content.getBoundsInLocal().getHeight() + 4));
                stage.getScene().getRoot().applyCss();
                stage.getScene().getRoot().layout();
                // Horizontal overflow check: the page must never need a horizontal scrollbar.
                double contentWidth = content.getBoundsInLocal().getWidth();
                double viewWidth = scrollHolder[0].getViewportBounds().getWidth();
                System.out.printf("%s: viewport=%.0f content=%.0f %s%n", name, viewWidth, contentWidth,
                        contentWidth > viewWidth + 1 ? "HORIZONTAL OVERFLOW" : "ok");
                WritableImage image = stage.getScene().snapshot(null);
                write(image, (int) height, outDir.resolve(name + ".png").toFile());
            } catch (Throwable t) {
                t.printStackTrace();
            } finally {
                done.countDown();
            }
        });
        done.await();
    }

    private static void write(WritableImage image, int cropHeight, File file) throws Exception {
        int w = (int) image.getWidth();
        int h = Math.min((int) image.getHeight(), cropHeight);
        BufferedImage buffered = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader reader = image.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                buffered.setRGB(x, y, reader.getArgb(x, y));
            }
        }
        ImageIO.write(buffered, "png", file);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
