/***
 Copyright (c) 2015 CommonsWare, LLC

 Licensed under the Apache License, Version 2.0 (the "License"); you may
 not use this file except in compliance with the License. You may obtain
 a copy of the License at http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

package com.commonsware.cwac.cam2.demo;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;
import com.commonsware.cwac.cam2.AbstractCameraActivity;
import com.commonsware.cwac.cam2.CameraActivity;
import com.commonsware.cwac.security.RuntimePermissionUtils;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import de.greenrobot.event.EventBus;
import static android.Manifest.permission.CAMERA;
import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;

public class MainActivity extends Activity {
  private static final String[] PERMS_ALL={
    CAMERA,
    WRITE_EXTERNAL_STORAGE
  };
  private static final int REQUEST_PORTRAIT_RFC=1337;
  private static final int REQUEST_PORTRAIT_FFC=REQUEST_PORTRAIT_RFC+1;
  private static final int REQUEST_LANDSCAPE_RFC=REQUEST_PORTRAIT_RFC+2;
  private static final int REQUEST_LANDSCAPE_FFC=REQUEST_PORTRAIT_RFC+3;
  private static final int RESULT_PERMS_ALL=REQUEST_PORTRAIT_RFC+4;
  private static final String STATE_PAGE="cwac_cam2_demo_page";
  private static final String STATE_TEST_ROOT="cwac_cam2_demo_test_root";
  private ViewFlipper wizardBody;
  private Button previous;
  private Button next;
  private File testRoot;
  private File testZip;
  private RuntimePermissionUtils utils;

  @TargetApi(23)
  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    if (!Environment.MEDIA_MOUNTED
      .equals(Environment.getExternalStorageState())) {
      Toast
        .makeText(this, "Cannot access external storage!",
          Toast.LENGTH_LONG)
        .show();
      finish();
    }

    setContentView(R.layout.main);

    utils=new RuntimePermissionUtils(this);

    wizardBody=(ViewFlipper)findViewById(R.id.wizard_body);
    previous=(Button)findViewById(R.id.previous);
    next=(Button)findViewById(R.id.next);

    if (savedInstanceState==null) {
      String filename="cam2_"+Build.MANUFACTURER+"_"+Build.PRODUCT
          +"_"+new SimpleDateFormat("yyyyMMdd'-'HHmmss").format(new Date());

      filename=filename.replaceAll(" ", "_");

      testRoot=new File(getExternalFilesDir(null), filename);
    }
    else {
      wizardBody.setDisplayedChild(savedInstanceState.getInt(STATE_PAGE, 0));
      testRoot=new File(savedInstanceState.getString(STATE_TEST_ROOT));
    }

    testZip=new File(testRoot.getAbsolutePath()+".zip");

    if (!haveNecessaryPermissions() && utils.useRuntimePermissions()) {
      requestPermissions(PERMS_ALL, RESULT_PERMS_ALL);
    }

    handlePage();
  }

  @Override
  protected void onStart() {
    super.onStart();

    EventBus.getDefault().register(this);
  }

  @Override
  protected void onStop() {
    super.onStop();

    EventBus.getDefault().unregister(this);
  }

  @Override
  protected void onDestroy() {
    if (!isChangingConfigurations()) {
      if (testRoot.exists()) {
        delete(testRoot);
      }

      if (testZip.exists()) {
        testZip.delete();

        MediaScannerConnection.scanFile(
          this,
          new String[]{testZip.getAbsolutePath()},
          null,
          null);
      }
    }

    super.onDestroy();
  }

  @Override
  protected void onSaveInstanceState(Bundle outState) {
    super.onSaveInstanceState(outState);

    outState.putInt(STATE_PAGE, wizardBody.getDisplayedChild());
    outState.putString(STATE_TEST_ROOT,
      testRoot.getAbsolutePath());
  }

  @Override
  protected void onActivityResult(final int requestCode,
                                  final int resultCode,
                                  final Intent data) {
    switch(requestCode) {
      case REQUEST_PORTRAIT_RFC:
        Runnable r=new Runnable() {
          @Override
          public void run() {
            capturePortraitFFC();
          }
        };

        if (resultCode==Activity.RESULT_CANCELED) {
          wizardBody.postDelayed(r, 2000);
        }
        else {
          r.run();
        }

        break;

      case REQUEST_PORTRAIT_FFC:
        wizardBody.showNext();
        handlePage();
        break;

      case REQUEST_LANDSCAPE_RFC:
        r=new Runnable() {
          @Override
          public void run() {
            captureLandscapeFFC();
          }
        };

        if (resultCode==Activity.RESULT_CANCELED) {
          wizardBody.postDelayed(r, 2000);
        }
        else {
          r.run();
        }

        break;

      case REQUEST_LANDSCAPE_FFC:
        wizardBody.showNext();
        handlePage();
        break;
    }
  }

  @Override
  public void onRequestPermissionsResult(int requestCode,
                                         String[] permissions,
                                         int[] grantResults) {
    if (!haveNecessaryPermissions()) {
      Toast.makeText(this, R.string.msg_perms_missing,
        Toast.LENGTH_LONG).show();
      finish();
    }
  }

  private boolean haveNecessaryPermissions() {
    return(utils.hasPermission(CAMERA) &&
      utils.hasPermission(WRITE_EXTERNAL_STORAGE));
  }

  private void handlePage() {
    switch(wizardBody.getDisplayedChild()) {
      case 0:
        handlePortraitPage();
        break;

      case 1:
        handleLandscapePage();
        break;

      case 2:
        handleCompletionPage();
        break;
    }
  }

  private void handlePortraitPage() {
    previous.setEnabled(false);
    next.setEnabled(true);
    next.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        capturePortraitRFC();
      }
    });
  }

  public void onEventMainThread(InitCaptureCompletedEvent event) {
    Intent i=new CameraActivity.IntentBuilder(this)
        .skipConfirm()
        .facing(AbstractCameraActivity.Facing.BACK)
        .facingExactMatch()
        .to(new File(testRoot, "portrait-rear.jpg"))
        .updateMediaStore()
        .debug()
        .build();

    startActivityForResult(i, REQUEST_PORTRAIT_RFC);
  }

  public void onEventMainThread(CompleteOutputCompletedEvent event) {
    findViewById(R.id.progress).setVisibility(View.GONE);
    findViewById(R.id.results).setVisibility(View.VISIBLE);

    TextView path=(TextView)findViewById(R.id.path);
    int extRootLength=Environment.getExternalStorageDirectory().getAbsolutePath().length();

    path.setText(testZip.getAbsolutePath().substring(extRootLength + 1));

    previous.setEnabled(false);

    next.setEnabled(true);
    next.setText(R.string.finish);
    next.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        finish();
      }
    });

    Button share=(Button)findViewById(R.id.share);

    share.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        Intent i=new Intent(Intent.ACTION_SEND);

        i.setType("application/zip");
        i.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(testZip));
        i.putExtra(Intent.EXTRA_SUBJECT,
          "CWAC-Cam2 Test Results");

        startActivity(
          Intent.createChooser(i, "Share Test Results"));
      }
    });
  }

  private void capturePortraitRFC() {
    next.setEnabled(false);
    new InitCaptureThread(testRoot).start();
  }

  private void handleLandscapePage() {
    previous.setEnabled(true);
    previous.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        wizardBody.showPrevious();
        handlePage();
      }
    });

    next.setEnabled(true);
    next.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        captureLandscapeRFC();
      }
    });
  }

  private void captureLandscapeRFC() {
    previous.setEnabled(false);
    next.setEnabled(false);

    Intent i=new CameraActivity.IntentBuilder(this)
        .skipConfirm()
        .facing(AbstractCameraActivity.Facing.BACK)
        .facingExactMatch()
        .to(new File(testRoot, "landscape-rear.jpg"))
        .updateMediaStore()
        .debug()
        .build();

    startActivityForResult(i, REQUEST_LANDSCAPE_RFC);
  }

  private void capturePortraitFFC() {
    Intent i=new CameraActivity.IntentBuilder(MainActivity.this)
      .skipConfirm()
      .facing(AbstractCameraActivity.Facing.FRONT)
      .facingExactMatch()
      .to(new File(testRoot, "portrait-front.jpg"))
      .debug()
      .updateMediaStore()
      .build();

    startActivityForResult(i, REQUEST_PORTRAIT_FFC);
  }

  private void captureLandscapeFFC() {
    Intent i=new CameraActivity.IntentBuilder(MainActivity.this)
      .skipConfirm()
      .facing(AbstractCameraActivity.Facing.FRONT)
      .facingExactMatch()
      .to(new File(testRoot, "landscape-front.jpg"))
      .updateMediaStore()
      .debug()
      .build();

    startActivityForResult(i, REQUEST_LANDSCAPE_FFC);
  }

  private void handleCompletionPage() {
    next.setEnabled(false);
    previous.setEnabled(false);
    new CompleteOutputThread(this, testRoot, testZip).start();
  }

  // inspired by http://pastebin.com/PqJyzQUx

  /**
   * Recursively deletes a directory and its contents.
   *
   * @param f The directory (or file) to delete
   * @return true if the delete succeeded, false otherwise
   */
  public boolean delete(File f) {
    if (f.isDirectory()) {
      for (File child : f.listFiles()) {
        if (!delete(child)) {
          return(false);
        }
      }
    }

    boolean result=f.delete();

    MediaScannerConnection.scanFile(
      this,
      new String[]{f.getAbsolutePath()},
      null,
      null);

    return(result);
  }

  // based on http://stackoverflow.com/a/16646691/115145

  private static void zipDirectory(Context ctxt, File dir,
                                   File zipFile) throws IOException {
    FileOutputStream fout = new FileOutputStream(zipFile);
    ZipOutputStream zout = new ZipOutputStream(fout);
    zipSubDirectory(ctxt, "", dir, zout);
    zout.flush();
    fout.getFD().sync();
    zout.close();
  }

  private static void zipSubDirectory(Context ctxt,
                                      String basePath, File dir,
                                      ZipOutputStream zout)
    throws IOException {
    byte[] buffer = new byte[4096];
    File[] files = dir.listFiles();

    for (File file : files) {
      if (file.isDirectory()) {
        String path = basePath + file.getName() + "/";
        zout.putNextEntry(new ZipEntry(path));
        zipSubDirectory(ctxt, path, file, zout);
        zout.closeEntry();
      }
      else {
        MediaScannerConnection.scanFile(
          ctxt,
          new String[]{file.getAbsolutePath()},
          null,
          null);

        FileInputStream fin = new FileInputStream(file);
        zout.putNextEntry(new ZipEntry(basePath + file.getName()));
        int length;
        while ((length = fin.read(buffer)) > 0) {
          zout.write(buffer, 0, length);
        }
        zout.closeEntry();
        fin.close();
      }
    }
  }

  private static class InitCaptureThread extends Thread {
    private final File testRoot;

    InitCaptureThread(File testRoot) {
      this.testRoot=testRoot;
    }

    @Override
    public void run() {
      testRoot.mkdirs();
      EventBus.getDefault().post(new InitCaptureCompletedEvent());
    }
  }

  private static class InitCaptureCompletedEvent {

  }

  private static class CompleteOutputThread extends Thread {
    private final File testRoot;
    private final File testZip;
    private final Context ctxt;

    CompleteOutputThread(Context ctxt, File testRoot, File testZip) {
      this.testRoot=testRoot;
      this.testZip=testZip;
      this.ctxt=ctxt.getApplicationContext();
    }

    @Override
    public void run() {
      Moshi moshi=new Moshi.Builder().build();
      JsonAdapter<DeviceInfo> jsonAdapter=moshi.adapter(DeviceInfo.class);

      try {
        String json=jsonAdapter.toJson(new DeviceInfo());
        File jsonFile=new File(testRoot, "device.json");
        FileOutputStream fos=new FileOutputStream(jsonFile);
        BufferedWriter out=new BufferedWriter(new OutputStreamWriter(fos));

        out.write(json);
        out.flush();
        fos.getFD().sync();
        out.close();
        zipDirectory(ctxt, testRoot, testZip);
        MediaScannerConnection.scanFile(
          ctxt,
          new String[]{testZip.getAbsolutePath()},
          null,
          null);
      }
      catch (IOException e) {
        Log.e(getClass().getSimpleName(), "Exception writing JSON", e);
      }

      EventBus.getDefault().post(new CompleteOutputCompletedEvent());
    }
  }

  private static class DeviceInfo {
    String manufacturer=Build.MANUFACTURER;
    String product=Build.PRODUCT;
    String build=Build.DISPLAY;
    String[] cpu={Build.CPU_ABI, Build.CPU_ABI2};
    String version=Build.VERSION.RELEASE;
    int api=Build.VERSION.SDK_INT;
    String brand=Build.BRAND;
    String model=Build.MODEL;
    String hardware=Build.HARDWARE;
  }

  private static class CompleteOutputCompletedEvent {

  }
}
