package org.archphene.bridge;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/** Device host for semantic-tree publication and reverse accessibility actions. */
public final class AccessibilityProbeActivity extends Activity {
    private static final String TREE = "{"
            + "\"viewportWidth\":360,\"viewportHeight\":640,\"nodes\":["
            + "{\"id\":1,\"parent\":0,\"role\":\"window\","
            + "\"text\":\"Archphene accessible window\",\"x\":0,\"y\":0,"
            + "\"width\":360,\"height\":640},"
            + "{\"id\":2,\"parent\":1,\"role\":\"button\","
            + "\"text\":\"Archphene accessible button\",\"x\":40,\"y\":80,"
            + "\"width\":280,\"height\":72,\"clickable\":true,"
            + "\"focusable\":true},"
            + "{\"id\":3,\"parent\":1,\"role\":\"edit\","
            + "\"text\":\"Accessible editor\",\"description\":\"Document text\","
            + "\"x\":40,\"y\":180,\"width\":280,\"height\":96,"
            + "\"editable\":true,\"focusable\":true},"
            + "{\"id\":4,\"parent\":1,\"role\":\"list\",\"text\":\"Scrollable list\","
            + "\"x\":40,\"y\":300,\"width\":280,\"height\":200,"
            + "\"scrollForward\":true},"
            + "{\"id\":5,\"parent\":1,\"role\":\"button\","
            + "\"text\":\"Disabled action\",\"x\":40,\"y\":520,"
            + "\"width\":280,\"height\":72,\"clickable\":true,"
            + "\"focusable\":true,\"enabled\":false},"
            + "{\"id\":11,\"parent\":0,\"role\":\"window\","
            + "\"text\":\"Secondary probe\",\"windowTitle\":\"Secondary probe\","
            + "\"x\":0,\"y\":0,\"width\":300,\"height\":240},"
            + "{\"id\":12,\"parent\":11,\"role\":\"button\","
            + "\"text\":\"Secondary accessible button\",\"x\":30,\"y\":60,"
            + "\"width\":240,\"height\":72,\"clickable\":true,"
            + "\"focusable\":true}]}";
    private static final String BAD_TREE = "{"
            + "\"viewportWidth\":360,\"viewportHeight\":640,\"nodes\":["
            + "{\"id\":1,\"parent\":2,\"role\":\"view\",\"x\":0,\"y\":0,"
            + "\"width\":10,\"height\":10},"
            + "{\"id\":2,\"parent\":1,\"role\":\"view\",\"x\":0,\"y\":0,"
            + "\"width\":10,\"height\":10}]}";

    private final ArchpheneAccessibilityBridge accessibility =
            new ArchpheneAccessibilityBridge();
    private AndroidCapabilityBroker broker;
    private ProbeView view;
    private ProbeView secondaryView;
    private Dialog secondaryDialog;
    private File brokerFile;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            Set<String> capabilities = BridgeCapabilities.read(this);
            view = new ProbeView(this);
            view.setBackgroundColor(0xff202124);
            setContentView(view);
            view.setProvider(accessibility.attach(view, 101));

            secondaryView = new ProbeView(this);
            secondaryView.setBackgroundColor(0xff303134);
            secondaryView.setProvider(accessibility.attach(secondaryView, 202));
            secondaryDialog = new Dialog(this);
            secondaryDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            secondaryDialog.setContentView(secondaryView);
            secondaryDialog.setCanceledOnTouchOutside(false);
            secondaryDialog.show();
            Window secondaryWindow = secondaryDialog.getWindow();
            if (secondaryWindow != null) {
                secondaryWindow.setDimAmount(0.15f);
                secondaryWindow.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                secondaryWindow.setLayout(300, 240);
            }
            accessibility.updateWindows(List.of(
                    new ArchpheneAccessibilityBridge.WindowDescriptor(
                            101, 0, true, 360, 640, "Archphene accessible window"),
                    new ArchpheneAccessibilityBridge.WindowDescriptor(
                            202, 101, false, 300, 240, "Secondary probe")), true);
            broker = new AndroidCapabilityBroker(this, capabilities, accessibility);
            broker.start();
            brokerFile = new File(getFilesDir(), "accessibility-broker-name");
            writeFile(brokerFile, broker.socketName());
            writeFile(new File(getFilesDir(), "accessibility-tree.json"), TREE);
            writeFile(new File(getFilesDir(), "bad-accessibility-tree.json"), BAD_TREE);
            handleActionIntent(getIntent());
        } catch (Exception error) {
            throw new IllegalStateException("Could not start accessibility probe", error);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleActionIntent(intent);
    }

    private void handleActionIntent(Intent intent) {
        if (intent == null) return;
        if (intent.getBooleanExtra("archphene_reorder_windows", false)) {
            accessibility.updateWindows(List.of(
                    new ArchpheneAccessibilityBridge.WindowDescriptor(
                            202, 101, false, 1, 1, ""),
                    new ArchpheneAccessibilityBridge.WindowDescriptor(
                            101, 0, true, 1, 1, "")), true);
            accessibility.sendNamedEvent(0, "content");
            return;
        }
        if (intent.getBooleanExtra("archphene_hide_secondary", false)) {
            if (secondaryView != null) accessibility.detach(secondaryView);
            if (secondaryDialog != null) secondaryDialog.dismiss();
            secondaryView = null;
            secondaryDialog = null;
            accessibility.updateWindows(List.of(
                    new ArchpheneAccessibilityBridge.WindowDescriptor(
                            101, 0, true, 360, 640, "Archphene accessible window")), true);
            if (view != null) {
                view.postDelayed(() -> accessibility.sendNamedEvent(0, "content"), 250);
            }
            return;
        }
        if (!intent.hasExtra("archphene_node")) return;
        int node = intent.getIntExtra("archphene_node", 0);
        String action = intent.getStringExtra("archphene_action");
        int androidAction;
        Bundle arguments = null;
        if ("click".equals(action)) {
            androidAction = AccessibilityNodeInfo.ACTION_CLICK;
        } else if ("scroll-forward".equals(action)) {
            androidAction = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
        } else if ("set-text".equals(action)) {
            androidAction = AccessibilityNodeInfo.ACTION_SET_TEXT;
            arguments = new Bundle();
            arguments.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    intent.getStringExtra("archphene_text"));
        } else {
            throw new IllegalArgumentException("Unknown probe accessibility action");
        }
        String providerName = intent.getStringExtra("archphene_provider");
        AccessibilityNodeProvider provider = null;
        if ("primary".equals(providerName) && view != null) {
            provider = view.getAccessibilityNodeProvider();
        } else if ("secondary".equals(providerName) && secondaryView != null) {
            provider = secondaryView.getAccessibilityNodeProvider();
        }
        boolean accepted = providerName == null
                ? accessibility.performAction(node, androidAction, arguments)
                : provider != null && provider.performAction(node, androidAction, arguments);
        boolean expectRejected = intent.getBooleanExtra(
                "archphene_expect_rejected", false);
        if (expectRejected) {
            if (accepted) throw new IllegalStateException(
                    "Accessibility action was unexpectedly accepted");
            return;
        }
        if (!accepted) {
            throw new IllegalStateException("Accessibility action was rejected");
        }
    }

    private static void writeFile(File file, String value) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
    }

    @Override
    protected void onDestroy() {
        if (broker != null) broker.close();
        if (secondaryView != null) accessibility.detach(secondaryView);
        if (secondaryDialog != null) secondaryDialog.dismiss();
        if (view != null) accessibility.detach(view);
        if (brokerFile != null) brokerFile.delete();
        super.onDestroy();
    }

    private static final class ProbeView extends View {
        private AccessibilityNodeProvider provider;

        ProbeView(Context context) {
            super(context);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        }

        void setProvider(AccessibilityNodeProvider provider) {
            this.provider = provider;
        }

        @Override
        public AccessibilityNodeProvider getAccessibilityNodeProvider() {
            return provider;
        }
    }
}
