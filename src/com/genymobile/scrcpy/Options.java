package com.genymobile.scrcpy;

import com.genymobile.scrcpy.device.Device;
import com.genymobile.scrcpy.model.CodecOption;
import com.genymobile.scrcpy.model.NewDisplay;
import com.genymobile.scrcpy.model.Orientation;
import com.genymobile.scrcpy.model.Size;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.video.VideoCodec;
import com.genymobile.scrcpy.wrappers.WindowManager;

import android.graphics.Rect;
import android.util.Pair;

import java.util.List;
import java.util.Locale;

public class Options {

    private Ln.Level logLevel = Ln.Level.DEBUG;
    private int maxSize;
    private int minSizeAlignment = 1;
    private VideoCodec videoCodec = VideoCodec.H264;
    private int videoBitRate = 8000000;
    private float maxFps;
    private int iFrameInterval = 10; // seconds
    private float angle;
    private Rect crop;
    private int displayId;
    private int displayImePolicy = -1;
    private List<CodecOption> videoCodecOptions;

    private String videoEncoder;
    private boolean clipboardAutosync = true;
    private boolean downsizeOnError = true;
    private boolean powerOn = true;

    private NewDisplay newDisplay;
    private boolean vdDestroyContent = true;
    private boolean vdSystemDecorations = true;
    private boolean flexDisplay;

    private boolean keepActive;
    private boolean ignoreVideoEncoderConstraints;

    private Orientation.Lock captureOrientationLock = Orientation.Lock.Unlocked;
    private Orientation captureOrientation = Orientation.Orient0;

    // Not exposed through CarLinkServer.Config; kept for driving the upstream key=value parsing directly
    private boolean sendFrameMeta = true; // send PTS so that the receiving head unit may record properly

    public Ln.Level getLogLevel() {
        return logLevel;
    }

    public int getMaxSize() {
        return maxSize;
    }

    public int getMinSizeAlignment() {
        return minSizeAlignment;
    }

    public VideoCodec getVideoCodec() {
        return videoCodec;
    }

    public int getVideoBitRate() {
        return videoBitRate;
    }

    public float getMaxFps() {
        return maxFps;
    }

    public int getIFrameInterval() {
        return iFrameInterval;
    }

    public float getAngle() {
        return angle;
    }

    public Rect getCrop() {
        return crop;
    }

    public int getDisplayId() {
        return displayId;
    }

    public int getDisplayImePolicy() {
        return displayImePolicy;
    }

    public List<CodecOption> getVideoCodecOptions() {
        return videoCodecOptions;
    }

    public String getVideoEncoder() {
        return videoEncoder;
    }

    public boolean getClipboardAutosync() {
        return clipboardAutosync;
    }

    public boolean getDownsizeOnError() {
        return downsizeOnError;
    }

    public boolean getPowerOn() {
        return powerOn;
    }

    public NewDisplay getNewDisplay() {
        return newDisplay;
    }

    public Orientation getCaptureOrientation() {
        return captureOrientation;
    }

    public Orientation.Lock getCaptureOrientationLock() {
        return captureOrientationLock;
    }

    public boolean getVDDestroyContent() {
        return vdDestroyContent;
    }

    public boolean getVDSystemDecorations() {
        return vdSystemDecorations;
    }

    public boolean getKeepActive() {
        return keepActive;
    }

    public boolean getFlexDisplay() {
        return flexDisplay;
    }

    public boolean getIgnoreVideoEncoderConstraints() {
        return ignoreVideoEncoderConstraints;
    }

    public boolean getSendFrameMeta() {
        return sendFrameMeta;
    }

    @SuppressWarnings("MethodLength")
    public static Options parse(String... args) {
        if (args.length < 1) {
            throw new IllegalArgumentException("Missing client version");
        }

        String clientVersion = args[0];
        if (!clientVersion.equals(BuildConfig.VERSION_NAME)) {
            throw new IllegalArgumentException(
                    "The server version (" + BuildConfig.VERSION_NAME + ") does not match the client " + "(" + clientVersion + ")");
        }

        Options options = new Options();

        for (int i = 1; i < args.length; ++i) {
            String arg = args[i];
            int equalIndex = arg.indexOf('=');
            if (equalIndex == -1) {
                throw new IllegalArgumentException("Invalid key=value pair: \"" + arg + "\"");
            }
            String key = arg.substring(0, equalIndex);
            String value = arg.substring(equalIndex + 1);
            switch (key) {
                case "log_level":
                    options.logLevel = Ln.Level.valueOf(value.toUpperCase(Locale.ENGLISH));
                    break;
                case "video_codec":
                    VideoCodec videoCodec = VideoCodec.findByName(value);
                    if (videoCodec == null) {
                        throw new IllegalArgumentException("Video codec " + value + " not supported");
                    }
                    options.videoCodec = videoCodec;
                    break;
                case "max_size":
                    options.maxSize = Integer.parseInt(value);
                    break;
                case "min_size_alignment":
                    int align = Integer.parseInt(value);
                    if (align < 1 || align > 16 || (align & (align - 1)) != 0) {
                        throw new IllegalArgumentException("min_size_alignment (" + align + ") must be 1, 2, 4, 8 or 16");
                    }
                    options.minSizeAlignment = align;
                    break;
                case "video_bit_rate":
                    options.videoBitRate = Integer.parseInt(value);
                    break;
                case "max_fps":
                    options.maxFps = parseFloat("max_fps", value);
                    break;
                case "i_frame_interval":
                    options.iFrameInterval = Integer.parseInt(value);
                    if (options.iFrameInterval < 0) {
                        throw new IllegalArgumentException("Invalid i-frame interval: " + options.iFrameInterval);
                    }
                    break;
                case "angle":
                    options.angle = parseFloat("angle", value);
                    break;
                case "crop":
                    if (!value.isEmpty()) {
                        options.crop = parseCrop(value);
                    }
                    break;
                case "display_id":
                    options.displayId = Integer.parseInt(value);
                    break;
                case "video_codec_options":
                    options.videoCodecOptions = CodecOption.parse(value);
                    break;
                case "video_encoder":
                    if (!value.isEmpty()) {
                        options.videoEncoder = value;
                    }
                    break;
                case "clipboard_autosync":
                    options.clipboardAutosync = Boolean.parseBoolean(value);
                    break;
                case "downsize_on_error":
                    options.downsizeOnError = Boolean.parseBoolean(value);
                    break;
                case "power_on":
                    options.powerOn = Boolean.parseBoolean(value);
                    break;
                case "new_display":
                    options.newDisplay = parseNewDisplay(value);
                    break;
                case "vd_destroy_content":
                    options.vdDestroyContent = Boolean.parseBoolean(value);
                    break;
                case "vd_system_decorations":
                    options.vdSystemDecorations = Boolean.parseBoolean(value);
                    break;
                case "flex_display":
                    options.flexDisplay = Boolean.parseBoolean(value);
                    break;
                case "capture_orientation":
                    Pair<Orientation.Lock, Orientation> pair = parseCaptureOrientation(value);
                    options.captureOrientationLock = pair.first;
                    options.captureOrientation = pair.second;
                    break;
                case "display_ime_policy":
                    options.displayImePolicy = parseDisplayImePolicy(value);
                    break;
                case "keep_active":
                    options.keepActive = Boolean.parseBoolean(value);
                    break;
                case "ignore_video_encoder_constraints":
                    options.ignoreVideoEncoderConstraints = Boolean.parseBoolean(value);
                    break;
                case "send_frame_meta":
                    options.sendFrameMeta = Boolean.parseBoolean(value);
                    break;
                default:
                    Ln.w("Unknown server option: " + key);
                    break;
            }
        }

        if (options.newDisplay != null) {
            assert options.displayId == 0 : "Must not set both displayId and newDisplay";
            options.displayId = Device.DISPLAY_ID_NONE;
        }

        return options;
    }

    private static Rect parseCrop(String crop) {
        // input format: "width:height:x:y"
        String[] tokens = crop.split(":");
        if (tokens.length != 4) {
            throw new IllegalArgumentException("Crop must contains 4 values separated by colons: \"" + crop + "\"");
        }
        int width = Integer.parseInt(tokens[0]);
        int height = Integer.parseInt(tokens[1]);
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid crop size: " + width + "x" + height);
        }
        int x = Integer.parseInt(tokens[2]);
        int y = Integer.parseInt(tokens[3]);
        if (x < 0 || y < 0) {
            throw new IllegalArgumentException("Invalid crop offset: " + x + ":" + y);
        }
        return new Rect(x, y, x + width, y + height);
    }

    private static Size parseSize(String size) {
        // input format: "<width>x<height>"
        String[] tokens = size.split("x");
        if (tokens.length != 2) {
            throw new IllegalArgumentException("Invalid size format (expected <width>x<height>): \"" + size + "\"");
        }
        int width = Integer.parseInt(tokens[0]);
        int height = Integer.parseInt(tokens[1]);
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid non-positive size dimension: \"" + size + "\"");
        }
        return new Size(width, height);
    }

    private static float parseFloat(String key, String value) {
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid float value for " + key + ": \"" + value + "\"");
        }
    }

    private static NewDisplay parseNewDisplay(String newDisplay) {
        // Possible inputs:
        //  - "" (empty string)
        //  - "<width>x<height>/<dpi>"
        //  - "<width>x<height>"
        //  - "/<dpi>"
        if (newDisplay.isEmpty()) {
            return new NewDisplay();
        }

        String[] tokens = newDisplay.split("/");

        Size size;
        if (!tokens[0].isEmpty()) {
            size = parseSize(tokens[0]);
        } else {
            size = null;
        }

        int dpi;
        if (tokens.length >= 2) {
            dpi = Integer.parseInt(tokens[1]);
            if (dpi <= 0) {
                throw new IllegalArgumentException("Invalid non-positive dpi: " + tokens[1]);
            }
        } else {
            dpi = 0;
        }

        return new NewDisplay(size, dpi);
    }

    private static Pair<Orientation.Lock, Orientation> parseCaptureOrientation(String value) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Empty capture orientation string");
        }

        Orientation.Lock lock;
        if (value.charAt(0) == '@') {
            // Consume '@'
            value = value.substring(1);
            if (value.isEmpty()) {
                // Only '@': lock to the initial orientation (orientation is unused)
                return Pair.create(Orientation.Lock.LockedInitial, Orientation.Orient0);
            }
            lock = Orientation.Lock.LockedValue;
        } else {
            lock = Orientation.Lock.Unlocked;
        }

        return Pair.create(lock, Orientation.getByName(value));
    }

    private static int parseDisplayImePolicy(String value) {
        switch (value) {
            case "local":
                return WindowManager.DISPLAY_IME_POLICY_LOCAL;
            case "fallback":
                return WindowManager.DISPLAY_IME_POLICY_FALLBACK_DISPLAY;
            case "hide":
                return WindowManager.DISPLAY_IME_POLICY_HIDE;
            default:
                throw new IllegalArgumentException("Invalid display IME policy: " + value);
        }
    }
}
