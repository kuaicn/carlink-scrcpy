package com.genymobile.scrcpy.control;

import com.genymobile.scrcpy.model.Point;
import com.genymobile.scrcpy.model.Position;
import com.genymobile.scrcpy.model.Size;
import com.genymobile.scrcpy.util.AffineMatrix;

public final class PositionMapper {

    private final Size videoSize;
    private final Size targetSize;
    private final AffineMatrix videoToDeviceMatrix;

    public PositionMapper(Size videoSize, AffineMatrix videoToDeviceMatrix, Size targetSize) {
        this.videoSize = videoSize;
        this.videoToDeviceMatrix = videoToDeviceMatrix;
        this.targetSize = targetSize;
    }

    public static PositionMapper create(Size videoSize, AffineMatrix filterTransform, Size targetSize) {
        boolean convertToPixels = !videoSize.equals(targetSize) || filterTransform != null;
        AffineMatrix transform = filterTransform;
        if (convertToPixels) {
            AffineMatrix inputTransform = AffineMatrix.ndcFromPixels(videoSize);
            AffineMatrix outputTransform = AffineMatrix.ndcToPixels(targetSize);
            transform = outputTransform.multiply(transform).multiply(inputTransform);
        }

        return new PositionMapper(videoSize, transform, targetSize);
    }

    public Size getVideoSize() {
        return videoSize;
    }

    public Point map(Position position) {
        Size clientVideoSize = position.getScreenSize();
        if (!videoSize.equals(clientVideoSize)) {
            // The client sends a click relative to a video with wrong dimensions,
            // the device may have been rotated since the event was generated, so ignore the event
            return null;
        }

        Point point = position.getPoint();
        if (videoToDeviceMatrix != null) {
            point = videoToDeviceMatrix.apply(point);
        }

        // Keep the result inside the target display: rounding may land 1px past the edge (e.g. the last video pixel of a strong
        // downscale rounds up to the target size), and out-of-bounds injection would miss the target window
        int x = Math.min(Math.max(point.getX(), 0), targetSize.getWidth() - 1);
        int y = Math.min(Math.max(point.getY(), 0), targetSize.getHeight() - 1);
        if (x != point.getX() || y != point.getY()) {
            point = new Point(x, y);
        }
        return point;
    }
}
