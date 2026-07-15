package org.archphene.bridge;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

public final class DocumentOpenActivity extends Activity {
    private static final int REQUEST_OPEN_DOCUMENT = 1;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!BridgeCapabilities.read(this).contains(BridgeCapabilities.DOCUMENTS)) {
            finish();
            return;
        }
        if (state == null) {
            Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            picker.addCategory(Intent.CATEGORY_OPENABLE);
            picker.setType("text/*");
            picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(picker, REQUEST_OPEN_DOCUMENT);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_OPEN_DOCUMENT && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            Uri uri = data.getData();
            int flags = data.getFlags()
                    & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            Intent edit = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (edit == null) {
                finish();
                return;
            }
            edit.setAction(Intent.ACTION_EDIT);
            edit.setDataAndType(uri, data.getType() == null ? "text/plain" : data.getType());
            edit.addFlags(flags);
            startActivity(edit);
        }
        finish();
    }
}