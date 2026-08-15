package com.genymobile.scrcpy.model;

public final class NewDisplay {
    private Size size;
    private int dpi;

    public NewDisplay() {
        // Auto size and dpi
    }

    public NewDisplay(Size size, int dpi) {
        this.size = size;
        this.dpi = dpi;
    }

    public Size getSize() {
        return size;
    }

    public int getDpi() {
        return dpi;
    }
}
