package org.archphene.bridge;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
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
            + "\"editable\":true,\"focusable\":true}]}";
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
    private File brokerFile;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            Set<String> capabilities = BridgeCapabilities.read(this);
            view = new ProbeView(this, accessibility);
            view.setBackgroundColor(0xff202124);
            setContentView(view);
            accessibility.attach(view);
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
        if (intent == null || !intent.hasExtra("archphene_node")) return;
        int node = intent.getIntExtra("archphene_node", 0);
        String action = intent.getStringExtra("archphene_action");
        int androidAction;
        Bundle arguments = null;
        if ("click".equals(action)) {
            androidAction = AccessibilityNodeInfo.ACTION_CLICK;
        } else if ("set-text".equals(action)) {
            androidAction = AccessibilityNodeInfo.ACTION_SET_TEXT;
            arguments = new Bundle();
            arguments.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    intent.getStringExtra("archphene_text"));
        } else {
            throw new IllegalArgumentException("Unknown probe accessibility action");
        }
        if (!accessibility.performAction(node, androidAction, arguments)) {
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
        if (view != null) accessibility.detach(view);
        if (brokerFile != null) brokerFile.delete();
        super.onDestroy();
    }

    private static final class ProbeView extends View {
        private final AccessibilityNodeProvider provider;

        ProbeView(Context context, AccessibilityNodeProvider provider) {
            super(context);
            this.provider = provider;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        }

        @Override
        public AccessibilityNodeProvider getAccessibilityNodeProvider() {
            return provider;
        }
    }
}
