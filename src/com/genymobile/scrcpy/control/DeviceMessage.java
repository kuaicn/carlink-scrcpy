package com.genymobile.scrcpy.control;

public final class DeviceMessage {

    public static final int TYPE_CLIPBOARD = 0;
    public static final int TYPE_ACK_CLIPBOARD = 1;
    // 2 was TYPE_UHID_OUTPUT upstream (UHID support is removed in this fork); 3 is a CarLink protocol extension
    // (docs/carlink-protocol.md): a payload-less liveness signal, sent periodically so that an idle session
    // (static screen, no input) still produces observable traffic on the control channel
    public static final int TYPE_HEARTBEAT = 3;

    private int type;
    private String text;
    private long sequence;

    private DeviceMessage() {
    }

    public static DeviceMessage createClipboard(String text) {
        DeviceMessage event = new DeviceMessage();
        event.type = TYPE_CLIPBOARD;
        event.text = text;
        return event;
    }

    public static DeviceMessage createAckClipboard(long sequence) {
        DeviceMessage event = new DeviceMessage();
        event.type = TYPE_ACK_CLIPBOARD;
        event.sequence = sequence;
        return event;
    }

    public static DeviceMessage createHeartbeat() {
        DeviceMessage event = new DeviceMessage();
        event.type = TYPE_HEARTBEAT;
        return event;
    }

    public int getType() {
        return type;
    }

    public String getText() {
        return text;
    }

    public long getSequence() {
        return sequence;
    }
}
