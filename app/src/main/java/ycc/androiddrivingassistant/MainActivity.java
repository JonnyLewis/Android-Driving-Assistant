package ycc.androiddrivingassistant;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.speech.tts.TextToSpeech;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.util.SparseArray;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.vision.Frame;
import com.google.android.gms.vision.text.TextBlock;
import com.google.android.gms.vision.text.TextRecognizer;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.JavaCameraView;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import ycc.androiddrivingassistant.ui.ScreenInterface;
import ycc.androiddrivingassistant.ui.SignUiRunnable;
import ycc.androiddrivingassistant.ui.SpeedUiRunnable;

public class MainActivity extends AppCompatActivity implements CameraBridgeViewBase.CvCameraViewListener2, ScreenInterface, TextToSpeech.OnInitListener {
    private static final String TAG = "MainActivity";
    JavaCameraView javaCameraView;
    TextRecognizer textRecognizer;
    ImageView signImageView;
    TextView speedTextView;
    FloatingActionButton fabSettings, fabResolutions, fabGps;
    Animation FabOpen, FabClose, FabRotateCw, FabRotateAntiCw;
    Boolean isOpen = false;


    Mat mRgba, mGray, circles;
    Mat mRed, mGreen, mBlue, mHue_hsv, mSat_hsv, mVal_hsv, mHue_hls, mSat_hls, mLight_hls;
    Mat hsv, hls, rgba, gray;
    Mat mNew, mask, mEdges;
    Rect signRegion;

    Bitmap bm;
    Boolean newSignFlag = false;

    int imgWidth, imgHeight;
    int rows, cols, left, width;
    double top, middleX, bottomY;

    double vehicleCenterX1, vehicleCenterY1, vehicleCenterX2, vehicleCenterY2, laneCenterX, laneCenterY;

    TextToSpeech tts;

    SignUiRunnable signUiRunnable = new SignUiRunnable();
    SpeedUiRunnable speedUiRunnable = new SpeedUiRunnable();

    BaseLoaderCallback mLoaderCallback = new BaseLoaderCallback(this) {
        @Override
        public void onManagerConnected(int status) {
            switch (status) {
                case BaseLoaderCallback.SUCCESS: {
                    javaCameraView.enableView();
                    break;
                }
                default: {
                    super.onManagerConnected(status);
                    break;
                }
            }
            super.onManagerConnected(status);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        getPermissions();
        setUpCameraServices();

        IntentFilter filter = new IntentFilter("ycc.androiddrivingassistant.UPDATE_SPEED");
        this.registerReceiver(new LocationBroadcastReceiver(), filter);

        textRecognizer = new TextRecognizer.Builder(this).build();
        if (!textRecognizer.isOperational()) {
            Log.w(TAG, "Detector dependencies are not yet available.");

            IntentFilter lowStorageFilter = new IntentFilter(Intent.ACTION_DEVICE_STORAGE_LOW);
            boolean hasLowStorage = registerReceiver(null, lowStorageFilter) != null;

            if (hasLowStorage) {
                Toast.makeText(this,"Low Storage: Speed Limit detection will not work.", Toast.LENGTH_LONG).show();
                Log.w(TAG, "Low Storage");
            }
        }

        tts = new TextToSpeech(this, this);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        javaCameraView = (JavaCameraView)findViewById(R.id.java_camera_view);
        javaCameraView.setVisibility(SurfaceView.VISIBLE);
        javaCameraView.setCvCameraViewListener(this);
//        javaCameraView.enableFpsMeter();
        javaCameraView.setMaxFrameSize(imgWidth, imgHeight);

        speedTextView = (TextView) findViewById(R.id.speed_text_view);
        signImageView = (ImageView) findViewById(R.id.sign_image_view);

        fabSettings = (FloatingActionButton) findViewById(R.id.fab_settings);
        fabResolutions = (FloatingActionButton) findViewById(R.id.fab_resolution);
        fabGps = (FloatingActionButton) findViewById(R.id.fab_gps);

        setViewClickListeners();
        FabOpen = AnimationUtils.loadAnimation(this, R.anim.fab_open);
        FabClose = AnimationUtils.loadAnimation(this, R.anim.fab_close);
        FabRotateCw = AnimationUtils.loadAnimation(this, R.anim.rotate_clockwise);
        FabRotateAntiCw = AnimationUtils.loadAnimation(this, R.anim.rotate_anticlockwise);

        signUiRunnable.setSignImageView(signImageView);
        speedUiRunnable.setSpeedTextView(speedTextView);
        SharedPreferences sharedPreferences;
        sharedPreferences = getSharedPreferences("Prefs", Context.MODE_PRIVATE);
        signUiRunnable.setSignVal(sharedPreferences.getInt("last_speed", 0));
        Log.i(TAG, "onCreate: ---------------------------------------------" + sharedPreferences.getInt("last_speed", 0));
        signUiRunnable.run();
    }

    @Override
    public void onCameraViewStarted(int w, int h) {
        rows = h;
        cols = w;
        left = rows / 8;
        width = cols - left;
        top = rows / 2.5;
        middleX = w /2;
        bottomY = h * .95;

        vehicleCenterX1 = middleX;
        vehicleCenterX2 = middleX;
        vehicleCenterY1 = bottomY-(rows/7);
        vehicleCenterY2 = bottomY-(rows/20);
        laneCenterX = 0;
        laneCenterY = (bottomY-(rows/7) + bottomY-(rows/20)) / 2;

        mRgba = new Mat();
        mGray = new Mat();

        circles = new Mat();
        mRed = new Mat();
        mGreen = new Mat();
        mBlue = new Mat();
        mHue_hls = new Mat();
        mLight_hls = new Mat();
        mSat_hls = new Mat();
        mHue_hsv = new Mat();
        mSat_hsv = new Mat();
        mVal_hsv = new Mat();

        hsv = new Mat();
        hls = new Mat();
        gray = new Mat();
        rgba = new Mat();

        mNew = new Mat();
        mask = new Mat();
        mEdges = new Mat();
    }

    @Override
    public void onCameraViewStopped() {
        mRgba.release();
        mGray.release();
        circles.release();
        mRed.release();
        mGreen.release();
        mBlue.release();
    }

    private Size ksize = new Size(5, 5);
    private double sigma = 3;
    private Point blurPt = new Point(3, 3);

    /******************************************************************************************
     * mRed, mGreen, mBlue, m-_hsv, m-_hls :  Mats of respective channels of ROI
     * mCombined : combined mat of canny edges and mask for yellow and white
     * hsv, hls, rgb : color space mats of ROI
     ******************************************************************************************/
    @Override
    public Mat onCameraFrame(CameraBridgeViewBase.CvCameraViewFrame inputFrame) {
        mRgba = inputFrame.rgba();
        mGray = inputFrame.gray();

        Imgproc.blur(mGray, mGray, new Size(5, 5), new Point(2, 2));
        Imgproc.GaussianBlur(mRgba, mRgba, ksize, sigma);

        Mat rgbaInnerWindow;
        Mat lines = new Mat();
        /* rgbaInnerWindow & mIntermediateMat = ROI Mats */
        rgbaInnerWindow = mRgba.submat((int)top, rows, left, width);
        rgbaInnerWindow.copyTo(rgba);
        Imgproc.cvtColor(rgbaInnerWindow, gray, Imgproc.COLOR_RGB2GRAY);
        Imgproc.cvtColor(rgbaInnerWindow, hsv, Imgproc.COLOR_RGB2HSV);
        Imgproc.cvtColor(rgbaInnerWindow, hls, Imgproc.COLOR_RGB2HLS);

        splitRGBChannels(rgba, hsv, hls);
        applyThreshold();
        Imgproc.erode(mask, mask, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3,3)));
        Imgproc.dilate(mask, mask, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3)));
        Imgproc.Canny(mask, mEdges, 50, 150);

        Imgproc.resize(mask, mNew, new Size(imgWidth, imgHeight));
        Imgproc.HoughCircles(mGray, circles, Imgproc.CV_HOUGH_GRADIENT, 2, 2000, 175, 120, 20, 100);

        if (circles.cols() > 0) {
            for (int x=0; x < Math.min(circles.cols(), 5); x++ ) {
                double circleVec[] = circles.get(0, x);

                if (circleVec == null) {
                    break;
                }

                Point center = new Point((int) circleVec[0], (int) circleVec[1]);
                int radius = 1;
                radius = (int) circleVec[2];

                int val = (radius*2) + 20;
                // defines the ROI
                signRegion = new Rect((int) (center.x - radius - 10), (int) (center.y - radius - 10), val, val);

                if (!newSignFlag) {
                    analyzeObject(inputFrame.rgba(), signRegion, radius);
                }
//                Log.i(TAG, "onCreate: " + Math.abs(radius*2));
            }
        }

        circles.release();

        Imgproc.line(mRgba, new Point(vehicleCenterX1, vehicleCenterY1), new Point(vehicleCenterX2, vehicleCenterY2), new Scalar(0, 155, 0), 2, 8);
        Imgproc.HoughLinesP(mEdges, lines, 1, Math.PI/180, 50, 110, 50);
        if (lines.rows() > 0) {
            getAverageSlopes(lines);
        }

        rgbaInnerWindow.release();
        Imgproc.rectangle(mRgba, new Point(left, top), new Point(cols-left, bottomY), new Scalar(0, 255, 0), 2);

        return mRgba;
    }

    int speedingCount = 0;
    ToneGenerator toneGen1 = new ToneGenerator(AudioManager.STREAM_MUSIC, 75);

    public class LocationBroadcastReceiver extends BroadcastReceiver {
        private static final String TAG = "BroadcastReceiver";
        @Override
        public void onReceive(Context context, Intent intent) {
            double vehicleSpeed = Objects.requireNonNull(intent.getExtras()).getDouble("speed");
            Log.e(TAG, "onReceive: " + vehicleSpeed);

            speedUiRunnable.setSpeedVal(vehicleSpeed);
            runOnUiThread(speedUiRunnable);
            if (vehicleSpeed > signUiRunnable.getSignVal() && signUiRunnable.getSignVal() > 0) {
                speedingCount += 1;
                if (speedingCount >= 5) {
                    try {
                        toneGen1.startTone(ToneGenerator.TONE_CDMA_HIGH_L, 200);
                    } catch (Exception e) {
                        toneGen1.release();
                        toneGen1 = new ToneGenerator(AudioManager.STREAM_MUSIC, 75);
                        Log.e(TAG, "onReceive: ", e);
                    }
                }
            } else {
                speedingCount = 0;
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (javaCameraView != null)
            javaCameraView.disableView();

        SharedPreferences.Editor editor = getSharedPreferences("Prefs", MODE_PRIVATE).edit();
        Log.i(TAG, "onPause: Latest detected speed limit: " + signUiRunnable.getSignVal());
        editor.putInt("last_speed", signUiRunnable.getSignVal());
        editor.apply();

        // stop updates to save battery
        stopService(new Intent(this, LocationService.class));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (javaCameraView != null)
            javaCameraView.disableView();
        stopService(new Intent(this, LocationService.class));
    }

    @Override
    protected void onResume() {
        SharedPreferences sharedPreferences = getSharedPreferences("Prefs", MODE_PRIVATE);
        super.onResume();
        if (OpenCVLoader.initDebug()) {
            Log.d(TAG, "OpenCV initialize success");
            mLoaderCallback.onManagerConnected(LoaderCallbackInterface.SUCCESS);
        }
        else {
            Log.d(TAG, "OpenCV initialize failed");
            OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION, this, mLoaderCallback);
        }
        int width = sharedPreferences.getInt("res_width", 1920);
        int height = sharedPreferences.getInt("res_height", 1080);
        javaCameraView.setMaxFrameSize(width, height);
        javaCameraView.disableView();
        javaCameraView.enableView();

        setFullscreen();
        // restart location updates when back in focus
        Intent locationServiceIntent = new Intent(this, LocationService.class);
        if (sharedPreferences.getBoolean("gps_enabled", true)) {
            startService(locationServiceIntent);
            speedTextView.setText("0.0km/hr");
        }
        else {
            stopService(locationServiceIntent);
            speedTextView.setText("GPS Disabled");
        }


    }

    public void splitRGBChannels(Mat rgb_split, Mat hsv_split, Mat hls_split) {
        List<Mat> rgbChannels = new ArrayList<>();
        List<Mat> hsvChannels = new ArrayList<>();
        List<Mat> hlsChannels = new ArrayList<>();

        Core.split(rgb_split, rgbChannels);
        Core.split(hsv_split, hsvChannels);
        Core.split(hls_split, hlsChannels);

        rgbChannels.get(0).copyTo(mRed);
        rgbChannels.get(1).copyTo(mGreen);
        rgbChannels.get(2).copyTo(mBlue);

        hsvChannels.get(0).copyTo(mHue_hsv);
        hsvChannels.get(1).copyTo(mSat_hsv);
        hsvChannels.get(2).copyTo(mVal_hsv);

        hlsChannels.get(0).copyTo(mHue_hls);
        hlsChannels.get(1).copyTo(mSat_hls);
        hlsChannels.get(2).copyTo(mLight_hls);
//
//
        for (int i = 0; i < rgbChannels.size(); i++){
            rgbChannels.get(i).release();
        }

        for (int i = 0; i < hsvChannels.size(); i++){
            hsvChannels.get(i).release();
        }

        for (int i = 0; i < hlsChannels.size(); i++){
            hlsChannels.get(i).release();
        }
    }

    public void applyThreshold() {
        Core.inRange(mRed, new Scalar(210), new Scalar(255), mRed);
//        Core.inRange(mGreen, new Scalar(225), new Scalar(255), mGreen);
//        Core.inRange(mBlue, new Scalar(200), new Scalar(255), mBlue);

//        Core.inRange(mHue_hsv, new Scalar(200), new Scalar(255), mHue_hsv);
//        Core.inRange(mSat_hsv, new Scalar(200), new Scalar(255), mSat_hsv);
        Core.inRange(mVal_hsv, new Scalar(210), new Scalar(255), mVal_hsv);

//        Core.inRange(mHue_hls, new Scalar(200), new Scalar(255), mHue_hls);
//        Core.inRange(mLight_hls, new Scalar(200), new Scalar(255), mLight_hls);
//        Core.inRange(mSat_hls, new Scalar(200), new Scalar(255), mSat_hls);

        Core.bitwise_and(mRed, mVal_hsv, mask);
    }

    int curSpeedVal = 50;
    String signValue = "";
    Boolean isRunning = false;

    public void analyzeObject(final Mat img, final Rect roi, final int radius) {
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                isRunning = true;
                Mat copy;
                try {
                    copy = new Mat(img, roi);
                    // Creates a bitmap with size of detected circle and stores the Mat into it
                    bm = Bitmap.createBitmap(Math.abs((radius * 2) + 20), Math.abs((radius * 2) + 20), Bitmap.Config.ARGB_8888);
                    Utils.matToBitmap(copy, bm);
                } catch (Exception e) {
                    bm = null;
                }

                if (bm != null) {
                    Frame imageFrame = new Frame.Builder().setBitmap(bm).build();
                    SparseArray<TextBlock> textBlocks = textRecognizer.detect(imageFrame);

                    for (int i = 0; i < textBlocks.size(); i++) {
                        TextBlock textBlock = textBlocks.get(textBlocks.keyAt(i));

                        if (!signValue.equals(textBlock.getValue())) {
                            signValue = textBlock.getValue();
                            setUISign(signValue);
                        }
                    }
                }
                isRunning = false;
            }
        };

        if (!isRunning) {
            Thread textDetectionThread = new Thread(runnable);
            textDetectionThread.run();
        }
    }

    public void setUISign(String val) {
        curSpeedVal = signUiRunnable.getSignVal();
        if (val.contains("60")) {
            signUiRunnable.setSignVal(60);
        } else if (val.contains("80")) {
            signUiRunnable.setSignVal(80);
        } else if (val.contains("100")) {
            signUiRunnable.setSignVal(100);
        } else if (val.contains("50")) {
            signUiRunnable.setSignVal(50);
        } else if (val.contains("120")) {
            signUiRunnable.setSignVal(120);
        } else if (val.contains("30")) {
            signUiRunnable.setSignVal(30);
        }
        Log.i(TAG, "setUISign:" + curSpeedVal + " -------------------------------" + signUiRunnable.getSignVal());
        if (curSpeedVal != signUiRunnable.getSignVal()) {
            tts.speak(signUiRunnable.getSignVal() + " kilometers per hour", TextToSpeech.QUEUE_FLUSH, null, "Speed Detected");
        }
        runOnUiThread(signUiRunnable);
    }

    public void getAverageSlopes(Mat lines) {
        List<Double> left_slopes = new ArrayList<>();
        List<Double> right_slopes = new ArrayList<>();
        List<Double> left_y_intercept = new ArrayList<>();
        List<Double> right_y_intercept = new ArrayList<>();

        for (int i=0; i<lines.rows(); i++) {
            double[] points = lines.get(i, 0);
            double x1, y1, x2, y2;

            try {
                x1 = points[0];
                y1 = points[1];
                x2 = points[2];
                y2 = points[3];

                Point p1 = new Point(x1, y1);
                Point p2 = new Point(x2, y2);

                double slope = (p2.y - p1.y) / (p2.x - p1.x);
                double y_intercept = 0;

                if (slope > 0.5 && slope < 2 ) { // Right lane
                    right_slopes.add(slope);
                    y_intercept = p1.y - (p1.x*slope);
                    right_y_intercept.add(y_intercept);
                }
                else if (slope > -2 && slope < -0.5) { // Left lane
                    left_slopes.add(slope);
                    y_intercept = p1.y - (p1.x*slope);
                    left_y_intercept.add(y_intercept);
                }

            } catch (Error e) {
                Log.e(TAG, "onCameraFrame: ", e);
            }
        }

        double avg_left_slope = 0;
        double avg_right_slope = 0;
        double avg_left_y_intercept = 0;
        double avg_right_y_intercept = 0;

        for (int i=0; i< right_slopes.size(); i++) {
            avg_right_slope += right_slopes.get(i);
            avg_right_y_intercept += right_y_intercept.get(i);
        }
        avg_right_slope /= right_slopes.size();
        avg_right_y_intercept /= right_y_intercept.size();

        for (int i=0; i< left_slopes.size(); i++) {
            avg_left_slope += left_slopes.get(i);
            avg_left_y_intercept += left_y_intercept.get(i);
        }
        avg_left_slope /= left_slopes.size();
        avg_left_y_intercept /= left_y_intercept.size();

        //x = (y-b)/m
        //y = xm + b
        double newLeftTopX = (-avg_left_y_intercept)/avg_left_slope;
        double newRightTopX = (0 - avg_right_y_intercept)/avg_right_slope;

        Point rightLanePt = new Point((imgHeight - avg_right_y_intercept)/avg_right_slope, imgHeight);
        Point leftLanePt = new Point((0), (-left*avg_left_slope)+avg_left_y_intercept);
//        Imgproc.putText(mRgba, "ROI Slope: " + avg_left_slope + " Other Slope: " + avg_left_y_intercept, new Point(0, 175), Core.FONT_HERSHEY_COMPLEX, 0.5, new Scalar(255, 0, 0));

        if (left_slopes.size() != 0) {
            Imgproc.line(mRgba, new Point(newLeftTopX + left, 0 + top), new Point(leftLanePt.x, leftLanePt.y + top), new Scalar(0, 255, 255), 5);
        }
        if (right_slopes.size() != 0) {
            Imgproc.line(mRgba, new Point(rightLanePt.x + left, rightLanePt.y + top), new Point(newRightTopX + left, 0 + top), new Scalar(255, 0, 255), 5);
        }
        if (right_slopes.size() != 0 && left_slopes.size() != 0) {
            double laneCenterX1 = (laneCenterY-top-avg_left_y_intercept)/avg_left_slope + left;
            double laneCenterX2 = (laneCenterY-top-avg_right_y_intercept)/avg_right_slope + left;
            laneCenterX = (laneCenterX1+laneCenterX2) / 2;
            Imgproc.line(mRgba, new Point(vehicleCenterX1, laneCenterY), new Point(laneCenterX, laneCenterY), new Scalar(0, 155, 0), 2, 8);
            Imgproc.circle(mRgba, new Point(laneCenterX, laneCenterY), 4, new Scalar(0, 0, 255), 6);
        }
    }

    @Override
    public void onInit(int status) {
        tts.setLanguage(Locale.ENGLISH);
    }

    private void setUpCameraServices() {
        SharedPreferences sharedPreferences = getSharedPreferences("Prefs", MODE_PRIVATE);
        boolean firstLaunch = false;
        SharedPreferences.Editor editor = sharedPreferences.edit();
        try {
            firstLaunch = sharedPreferences.getBoolean("first_launch", true);
            Log.i(TAG, "setUpCameraServices: " + firstLaunch);
        } catch (Exception e) {
            Log.e(TAG, "setUpCameraServices: ", e);
        }

        if (firstLaunch) {
            editor.putBoolean("gps_enabled", true);
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            try {
                assert manager != null;
                String cameraId = manager.getCameraIdList()[0];
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                assert map != null;

                for (android.util.Size size : map.getOutputSizes(SurfaceTexture.class)) {
                    float ratio = (float) size.getWidth() / (float) size.getHeight();
                    if (ratio >= 1.3 && size.getWidth() < 900) {
                        imgHeight = size.getHeight();
                        imgWidth = size.getWidth();
                        break;
                    }
                }
                editor.putInt("res_height", imgHeight);
                editor.putInt("res_width", imgWidth);
                Log.i(TAG, "setUpCameraServices: " + sharedPreferences);
            } catch (Error error) {
                Log.e(TAG, "onCreate: ", error);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
            editor.putBoolean("first_launch", false);
            editor.apply();
        }
        else {
            imgHeight = sharedPreferences.getInt("res_height", 1080);
            imgWidth = sharedPreferences.getInt("res_width", 1920);
        }
    }

    private void setViewClickListeners() {
        fabSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isOpen) {
                            fabResolutions.startAnimation(FabClose);
                            fabGps.startAnimation(FabClose);
                            fabSettings.startAnimation(FabRotateAntiCw);
                            fabResolutions.setClickable(false);
                            fabGps.setClickable(false);
                            isOpen = false;
                        } else {
                            fabResolutions.startAnimation(FabOpen);
                            fabGps.startAnimation(FabOpen);
                            fabSettings.startAnimation(FabRotateCw);
                            fabResolutions.setClickable(true);
                            fabGps.setClickable(true);
                            isOpen = true;
                        }
                    }
                });
            }
        });

        fabResolutions.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(getApplicationContext(), ResolutionSettingsActivity.class);
                startActivity(intent);
                Toast.makeText(getApplicationContext(), "Resolution Settings", Toast.LENGTH_SHORT).show();
            }
        });

        fabGps.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(getApplicationContext(), GpsSettingsActivity.class);
                startActivity(intent);
                Toast.makeText(getApplicationContext(), "GPS Settings", Toast.LENGTH_SHORT).show();
            }
        });

    }

    private void getPermissions() {
        if(!hasPermissions(this, PERMISSIONS)){
            ActivityCompat.requestPermissions(this, PERMISSIONS, PERMISSION_ALL);
        }
    }

    /* Checks if all the needed permissions are enabled and asks user if not */
    int PERMISSION_ALL = 1;
    String[] PERMISSIONS = {
            android.Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
    };

    public static boolean hasPermissions(Context context, String... permissions) {
        if (context != null && permissions != null) {
            for (String permission : permissions) {
                if (ActivityCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(context,"Camera permission is needed or \nthis application will not work.", Toast.LENGTH_LONG).show();
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public void setFullscreen() {
        this.getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }
}
