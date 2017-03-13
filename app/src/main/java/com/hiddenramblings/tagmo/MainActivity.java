package com.hiddenramblings.tagmo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import com.hiddenramblings.tagmo.editors.EditorSSB_;
import com.hiddenramblings.tagmo.editors.EditorTP_;

import org.androidannotations.annotations.AfterViews;
import org.androidannotations.annotations.Background;
import org.androidannotations.annotations.Click;
import org.androidannotations.annotations.EActivity;
import org.androidannotations.annotations.OptionsItem;
import org.androidannotations.annotations.OptionsMenu;
import org.androidannotations.annotations.TextChange;
import org.androidannotations.annotations.UiThread;
import org.androidannotations.annotations.ViewById;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Arrays;
import java.util.Calendar;

import static com.hiddenramblings.tagmo.TagWriter.compareRange;


@EActivity(R.layout.activity_main)
@OptionsMenu({R.menu.main_menu})
public class MainActivity extends AppCompatActivity /* implements TagCreateDialog.TagCreateListener */ {
    private static final String TAG = "MainActivity";

    private static final String DATA_DIR = "tagmo";

    private static final int FILE_LOAD_TAG = 0x100;
    private static final int FILE_LOAD_KEYS = 0x101;
    private static final int NFC_ACTIVITY = 0x102;
    private static final int EDIT_TAG = 0x103;
    private static final int FILE_LOAD_MERGE_TAG = 0x104;

    private static final byte SettingsInitialized = 1 << 4;
    private static final byte AppDataInitialized = 1 << 5;

    @ViewById(R.id.txtLockedKey)
    TextView txtLockedKey;
    @ViewById(R.id.txtUnfixedKey)
    TextView txtUnfixedKey;
    @ViewById(R.id.txtNFC)
    TextView txtNFC;
    @ViewById(R.id.txtTagId)
    TextView txtTagId;
    @ViewById(R.id.txtTagFile)
    TextView txtTagFile;

    @ViewById(R.id.btnSaveTag)
    Button btnSaveTag;
    @ViewById(R.id.btnLoadTag)
    Button btnLoadTag;
    @ViewById(R.id.btnWriteTagAuto)
    Button btnWriteTagAuto;
    @ViewById(R.id.btnWriteTagRaw)
    Button btnWriteTagRaw;
    @ViewById(R.id.btnRestoreTag)
    Button btnRestoreTag;
    @ViewById(R.id.btnMergeTag)
    Button btnMergeTag;
    @ViewById(R.id.btnEditDataSSB)
    Button btnEditDataSSB;
    @ViewById(R.id.btnViewHex)
    Button btnViewHex;

    @ViewById(R.id.cbAutoSaveOnScan)
    CheckBox cbAutoSaveOnScan;
    @ViewById(R.id.cbNoIDValidate)
    CheckBox cbNoIDValidate;

    byte[] currentTagData;
    boolean isDirty = false;
    KeyManager keyManager;
    NfcAdapter nfcAdapter;

    boolean keyWarningShown;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.verifyStoragePermissions();
    }

    @AfterViews
    protected void afterViews() {
        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        keyManager = new KeyManager(this);

        updateStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        startNfcMonitor();
        updateStatus();
    }

    @Override
    protected void onPause() {
        stopNfcMonitor();
        super.onPause();
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();

            if (action.equals(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED)) {
                updateStatus();
            }
        }
    };

    void startNfcMonitor() {
        if (nfcAdapter == null)
            return;
        IntentFilter filter = new IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED);
        this.registerReceiver(mReceiver, filter);
    }

    void stopNfcMonitor() {
        if (nfcAdapter == null)
            return;
        this.unregisterReceiver(mReceiver);
    }

    @UiThread
    void updateStatus() {
        boolean hasNfc = (nfcAdapter != null);
        boolean nfcEnabled = hasNfc && nfcAdapter.isEnabled();
        boolean hasFixed = this.keyManager.hasFixedKey();
        boolean hasUnfixed = this.keyManager.hasUnFixedKey();
        boolean hasKeys = hasFixed && hasUnfixed;
        boolean hasTag = currentTagData != null;

        if (!hasNfc) {
            txtNFC.setTextColor(Color.RED);
            txtNFC.setText("NFC not supported!");
        } else if (!nfcEnabled) {
            txtNFC.setTextColor(Color.RED);
            txtNFC.setText("NFC not enabled!");
        } else {
            txtNFC.setTextColor(Color.rgb(0x00, 0xAf, 0x00));
            txtNFC.setText("NFC enabled!");
        }

        if (!hasFixed) {
            txtLockedKey.setTextColor(Color.RED);
            txtLockedKey.setText("No Locked key!");
        } else {
            txtLockedKey.setTextColor(Color.rgb(0x00, 0xAf, 0x00));
            txtLockedKey.setText("Locked key OK.");
        }

        if (!hasUnfixed) {
            txtUnfixedKey.setTextColor(Color.RED);
            txtUnfixedKey.setText("No Unfixed key!");
        } else {
            txtUnfixedKey.setTextColor(Color.rgb(0x00, 0xAf, 0x00));
            txtUnfixedKey.setText("Unfixed key OK.");
        }

        btnWriteTagAuto.setEnabled(nfcEnabled && hasKeys && hasTag);
        btnWriteTagRaw.setEnabled(nfcEnabled && hasTag);
        btnRestoreTag.setEnabled(nfcEnabled && hasTag);
        btnSaveTag.setEnabled(hasTag && isDirty);
        btnMergeTag.setEnabled(hasTag);
        btnEditDataSSB.setEnabled(hasKeys && hasTag);
        btnViewHex.setEnabled(hasKeys && hasTag);

        if (!hasKeys && !keyWarningShown) {
            LogError("Not all keys loaded. Load keys using the menu.");
            keyWarningShown = true;
        }

        try {
            if (this.currentTagData != null) {
                byte[] charIdData = TagUtil.charIdDataFromTag(this.currentTagData);
                String charid = AmiiboDictionary.getDisplayName(charIdData);
                String uid = Util.bytesToHex(TagUtil.uidFromPages(this.currentTagData));
                txtTagId.setText("TagId: " + charid + " / " + uid);
                onTagLoaded(charIdData);
            } else {
                txtTagId.setText("TagId: <No tag loaded>");
                txtTagFile.setText("");
                this.isDirty = false;
                onTagLoaded(null);
            }
        } catch (Exception e) {
            LogError("Error parsing tag id", e);
            txtTagId.setText("TagID: <Error>");
            txtTagFile.setText("");
            this.isDirty = false;
            onTagLoaded(null);
        }
    }

    void onTagLoaded(byte[] charIdData) {
        Button edit_ssb = (Button) findViewById(R.id.btnEditDataSSB);
        Button edit_tp = (Button) findViewById(R.id.btnEditDataTP);

        if (charIdData == null) {
            edit_ssb.setVisibility(View.INVISIBLE);
            edit_tp.setVisibility(View.INVISIBLE);
        } else {
            AmiiboDictionary.AmiiboIdData ad = AmiiboDictionary.parseid(charIdData);
            int id = (ad.Brand << 16) + (ad.Variant << 8) + ad.Type;
            switch (id) {
                case 0x01030000: // Wolf Link; TODO: Make AmiiboDictinary IDS an enum
                    edit_ssb.setVisibility(View.INVISIBLE);
                    edit_tp.setVisibility(View.VISIBLE);
                    break;
                default:
                    edit_ssb.setVisibility(View.VISIBLE);
                    edit_tp.setVisibility(View.INVISIBLE);
                    break;
            }
        }
    }

    @Click(R.id.btnLoadTag)
    void loadTagFile() {
        showFileChooser("Load encrypted tag file for writing", "*/*", FILE_LOAD_TAG);
    }

    @OptionsItem(R.id.mnu_load_keys)
    void loadKeysClicked() {
        showFileChooser("Load key file", "*/*", FILE_LOAD_KEYS);
    }

    @Click(R.id.btnScanTag)
    void scanTag() {
        Intent intent = new Intent(this, NfcActivity_.class);
        intent.setAction(NfcActivity.ACTION_SCAN_TAG);
        startActivityForResult(intent, NFC_ACTIVITY);
    }

    @Click(R.id.btnWriteTagRaw)
    void writeToTagRaw() {
        if (this.currentTagData == null) {
            LogError("No tag loaded");
            return;
        }
        Intent intent = new Intent(this, NfcActivity_.class);
        intent.setAction(NfcActivity.ACTION_WRITE_TAG_RAW);
        intent.putExtra(NfcActivity.EXTRA_TAG_DATA, this.currentTagData);
        startActivityForResult(intent, NFC_ACTIVITY);
    }

    @Click(R.id.btnRestoreTag)
    void restoreTag() {
        if (this.currentTagData == null) {
            LogError("No tag loaded");
            return;
        }
        Intent intent = new Intent(this, NfcActivity_.class);
        intent.setAction(NfcActivity.ACTION_WRITE_TAG_DATA);
        intent.putExtra(NfcActivity.EXTRA_TAG_DATA, this.currentTagData);
        intent.putExtra(NfcActivity.EXTRA_IGNORE_TAG_ID, this.cbNoIDValidate.isChecked());
        startActivityForResult(intent, NFC_ACTIVITY);
    }

    @Click(R.id.btnWriteTagAuto)
    void writeToTagAuto() {
        if (this.currentTagData == null) {
            LogError("No tag loaded");
            return;
        }
        Intent intent = new Intent(this, NfcActivity_.class);
        intent.setAction(NfcActivity.ACTION_WRITE_TAG_FULL);
        intent.putExtra(NfcActivity.EXTRA_TAG_DATA, this.currentTagData);
        startActivityForResult(intent, NFC_ACTIVITY);
    }

    @Click(R.id.btnSaveTag)
    void saveTag() {
        if (this.currentTagData == null) {
            LogError("No tag loaded");
            return;
        }
        writeTagToFile(this.currentTagData);
    }

    @Click(R.id.btnMergeTag)
    void mergeTag() {
        if (this.currentTagData == null) {
            LogError("No tag loaded");
            return;
        }
        showFileChooser("Load tag file with user/game data for merging", "*/*", FILE_LOAD_MERGE_TAG);
    }

    @Click(R.id.btnEditDataSSB)
    void editSSBData() {
        if (this.currentTagData == null) {
            LogError("No tag loaded");
            return;
        }
        Intent intent = new Intent(this, EditorSSB_.class);
        intent.setAction(Actions.ACTION_EDIT_DATA);
        intent.putExtra(Actions.EXTRA_TAG_DATA, this.currentTagData);
        startActivityForResult(intent, EDIT_TAG);
    }

    @Click(R.id.btnEditDataTP)
    void editTPData() {
        if (this.currentTagData == null) {
            LogError("No tag loaded");
            return;
        }
        Intent intent = new Intent(this, EditorTP_.class);
        intent.setAction(Actions.ACTION_EDIT_DATA);
        intent.putExtra(Actions.EXTRA_TAG_DATA, this.currentTagData);
        startActivityForResult(intent, EDIT_TAG);
    }

    @Click(R.id.btnViewHex)
    void viewHex() {
        if (this.currentTagData == null) {
            LogError("No tag loaded");
            return;
        }
        Intent intent = new Intent(this, HexViewerActivity_.class);
        intent.setAction(Actions.ACTION_EDIT_DATA);
        intent.putExtra(Actions.EXTRA_TAG_DATA, this.currentTagData);
        startActivity(intent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK)
            return;

        Log.d(TAG, "onActivityResult");

        String action = null;
        switch (requestCode)
        {
            case EDIT_TAG:
                if (data == null) return;
                action = data.getAction();
                if (!Actions.ACTION_EDIT_COMPLETE.equals(action))
                    return;
                this.currentTagData = data.getByteArrayExtra(Actions.EXTRA_TAG_DATA);
                this.txtTagFile.setText("Unsaved");
                this.isDirty = true;
                //Enable save button
                this.updateStatus();
                if (this.currentTagData == null) {
                    LogError("Tag data is empty");
                    this.txtTagFile.setText("");
                    this.isDirty = false;
                    return;
                }
            case NFC_ACTIVITY:
                if (data == null) return;
                action = data.getAction();
                if (!NfcActivity.ACTION_NFC_SCANNED.equals(action))
                    return;
                this.currentTagData = data.getByteArrayExtra(NfcActivity.EXTRA_TAG_DATA);
                updateStatus();
                if (this.currentTagData == null) {
                    LogError("Tag data is empty");
                    this.txtTagFile.setText("");
                    this.isDirty = false;
                    return;
                }
                if (cbAutoSaveOnScan.isChecked())
                    writeTagToFile(this.currentTagData);
                else {
                    this.txtTagFile.setText("Unsaved");
                    this.isDirty = true;
                }
                break;
            case FILE_LOAD_KEYS:
                loadKey(data.getData());
                break;
            case FILE_LOAD_TAG:
                loadTagFile(data.getData());
                break;
            case FILE_LOAD_MERGE_TAG:
                mergeTagFile(this.currentTagData, data.getData());
                break;
        }
    }

    private void showFileChooser(String title, String mimeType, int resultCode) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");      //all files
        intent.addCategory(Intent.CATEGORY_OPENABLE);

        try {
            startActivityForResult(Intent.createChooser(intent, title), resultCode);
        } catch (android.content.ActivityNotFoundException ex) {
            LogError("Failed to show file open dialog. Please install a file manager app.");
            Log.e(TAG, ex.getMessage());
        }
    }

    private static final int REQUEST_EXTERNAL_STORAGE = 1;
    private static final String READ_EXTERNAL_STORAGE = "android.permission.READ_EXTERNAL_STORAGE";
    private static final String WRITE_EXTERNAL_STORAGE = "android.permission.WRITE_EXTERNAL_STORAGE";
    private static String[] PERMISSIONS_STORAGE = {
            READ_EXTERNAL_STORAGE,
            WRITE_EXTERNAL_STORAGE
    };

    void verifyStoragePermissions() {
        int permission = ContextCompat.checkSelfPermission(this, WRITE_EXTERNAL_STORAGE);
        if (permission != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, PERMISSIONS_STORAGE, REQUEST_EXTERNAL_STORAGE);
        }
    }


    @Background
    void loadKey(Uri uri) {
        try {
            this.keyManager.loadKey(uri);
        } catch (Exception e) {
            LogError("Error: " + e.getMessage());
        }
        updateStatus();
    }

    @Background
    void loadTagFile(final Uri uri) {
        try {
            InputStream strm = getContentResolver().openInputStream(uri);
            byte[] data = new byte[TagUtil.TAG_FILE_SIZE];
            try {
                int len = strm.read(data);
                if (len != TagUtil.TAG_FILE_SIZE)
                    throw new Exception("Invalid file size. was expecting " + TagUtil.TAG_FILE_SIZE);
            } finally {
                strm.close();
            }
            this.currentTagData = data;
            showToast("Loaded tag file.");
            this.txtTagFile.post(new Runnable() {
                public void run() {
                    try {
                        txtTagFile.setText(new File(URLDecoder.decode(uri.toString(), "UTF-8")).getName());
                    }
                    catch (UnsupportedEncodingException e)
                    {
                        LogError("Error getting filename: " + e.getMessage());
                    }
                }
            });
            this.isDirty = false;
        } catch (Exception e) {
            LogError("Error: " + e.getMessage());
        }
        updateStatus();
    }

    @Background
    void mergeTagFile(byte[] originalTagData, final Uri mergeFileUri) {
        boolean originalValid = false;
        try {
            TagUtil.validateTag(originalTagData);
            originalValid = true;
        } catch (Exception e) {
            LogError("Warning original tag is not valid");
        }

        try {
            InputStream strm = getContentResolver().openInputStream(mergeFileUri);
            byte[] mergeData = new byte[TagUtil.TAG_FILE_SIZE];
            try {
                int len = strm.read(mergeData);
                if (len != TagUtil.TAG_FILE_SIZE)
                    throw new Exception("Invalid file size. was expecting " + TagUtil.TAG_FILE_SIZE);
            } finally {
                strm.close();
            }

            byte[] originalCharIdData = TagUtil.charIdDataFromTag(originalTagData);
            byte[] mergeCharIdData = TagUtil.charIdDataFromTag(mergeData);

            if (!compareRange(originalCharIdData, mergeCharIdData, 0, originalCharIdData.length))
            {
                LogMessage("!!! WARNING !!!: Input and merge file are not the same amiibo.");
            }

            byte[] decryptedMergable = TagUtil.decrypt(keyManager, mergeData);

            byte mergeFileStatus = (byte) (decryptedMergable[0x02C] & 0x30);
            if (!Util.hasFlag(mergeFileStatus, AppDataInitialized))
            {
                if (!Util.hasFlag(mergeFileStatus, SettingsInitialized))
                {
                    throw new Exception("The merge file does not have settings data!");
                }
                throw new Exception("The merge file does not have app data!");
            }

            byte[] interim = TagUtil.decrypt(keyManager, originalTagData);

            byte originalFileStatus = (byte) (interim[0x02C] & 0x30);
            if (Util.hasFlag(originalFileStatus, AppDataInitialized) || Util.hasFlag(originalFileStatus, SettingsInitialized))
            {
                LogMessage("!!! WARNING !!!: The original file already has settings data. Overwriting...");
            }
            if (Util.hasFlag(originalFileStatus, AppDataInitialized))
            {
                LogMessage("!!! WARNING !!!: The original file already has app data. Overwriting...");
            }

            // Copy Amiibo Settings and App Data
            // Entirety of Cryptobuffer between two decrypted tags
            System.arraycopy(decryptedMergable, 0x02C, interim, 0x02C, 0x188);

            // Re-encrypt
            this.currentTagData = TagUtil.encrypt(keyManager, interim);
            showToast("Merged tag file.");

            this.txtTagFile.post(new Runnable() {
                public void run() {
                    txtTagFile.setText("Unsaved");
                }
            });
            this.isDirty = true;
        } catch (Exception e) {
            LogError("Error: " + e.getMessage());
        }
        updateStatus();
    }

    @Background
    protected void writeTagToFile(byte[] tagdata) {
        boolean valid = false;
        try {
            TagUtil.validateTag(tagdata);
            valid = true;
        } catch (Exception e) {
            LogError("Warning tag is not valid");
        }

        try {
            byte[] charIdData = TagUtil.charIdDataFromTag(this.currentTagData);
            String charid = AmiiboDictionary.getDisplayName(charIdData).replace("/", "-"); //prevent invalid filenames

            byte[] uid = Arrays.copyOfRange(tagdata, 0, 9);
            String uids = Util.bytesToHex(uid);
            final String fname = String.format("%1$s [%2$s] %3$ty%3$tm%3$te_%3$tH%3$tM%3$tS%4$s.bin", charid,  uids, Calendar.getInstance(), (valid ? "" : "_corrupted_"));

            File dir = new File(Environment.getExternalStorageDirectory(), DATA_DIR);
            if (!dir.isDirectory())
                dir.mkdir();

            File file = new File(dir.getAbsolutePath(), fname);

            Log.d(TAG, file.toString());
            FileOutputStream fos = new FileOutputStream(file);
            try {
                fos.write(tagdata);
            } finally {
                fos.close();
            }
            try {
                MediaScannerConnection.scanFile(this, new String[]{file.getAbsolutePath()}, null, null);
            } catch (Exception e) {
                Log.e(TAG, "Failed to refresh media scanner", e);
            }
            LogMessage("Wrote to file " + fname + " in tagmo directory.");
            showToast("Saved tag file.");
            this.txtTagFile.post(new Runnable() {
                public void run() {
                    txtTagFile.setText(fname);
                }
            });
            this.isDirty = false;
            updateStatus();
        } catch (Exception e) {
            LogError("Error writing to file: " + e.getMessage());
            this.txtTagFile.post(new Runnable() {
                public void run() {
                    txtTagFile.setText("Error saving");
                }
            });
        }
    }

    @UiThread
    void showToast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    @UiThread
    void LogMessage(String msg) {
        new AlertDialog.Builder(this).setMessage(msg)
        .setPositiveButton("Close", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {

            }
        }).show();
    }
    @UiThread
    void LogError(String msg, Throwable e) {
        new AlertDialog.Builder(this).setTitle("Error").setMessage(msg)
        .setPositiveButton("Close", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {

            }
        }).show();
    }
    @UiThread
    void LogError(String msg) {
        new AlertDialog.Builder(this).setTitle("Error").setMessage(msg)
        .setPositiveButton("Close", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {

            }
        }).show();
    }

}
