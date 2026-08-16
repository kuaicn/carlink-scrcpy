package com.genymobile.scrcpy.opengl;

import com.genymobile.scrcpy.model.Size;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.util.Threads;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;

public final class OpenGLRunner {

    // Bounded join() on shutdown()
    private static final long JOIN_TIMEOUT_MS = 2000;

    private static HandlerThread handlerThread;
    private static Handler handler;

    private EGLDisplay eglDisplay;
    private EGLContext eglContext;
    private EGLSurface eglSurface;

    private final OpenGLFilter filter;
    private final float[] overrideTransformMatrix;
    // Reused across frames when no override matrix is set (render() always runs on the GL thread);
    // SurfaceTexture.getTransformMatrix() fully overwrites all 16 elements on every call
    private final float[] transformMatrix = new float[16];

    private SurfaceTexture surfaceTexture;
    private Surface inputSurface;
    private int textureId;

    private boolean stopped;

    public OpenGLRunner(OpenGLFilter filter, float[] overrideTransformMatrix) {
        this.filter = filter;
        this.overrideTransformMatrix = overrideTransformMatrix;
    }

    public OpenGLRunner(OpenGLFilter filter) {
        this(filter, null);
    }

    public static synchronized void initOnce() {
        if (handlerThread == null) {
            handlerThread = new HandlerThread("OpenGLRunner");
            handlerThread.start();
            handler = new Handler(handlerThread.getLooper());
        }
    }

    public static void shutdown() throws InterruptedException {
        HandlerThread thread;
        synchronized (OpenGLRunner.class) {
            thread = handlerThread;
            // Reset so that a later session creates a fresh thread (a quit thread would silently drop posted runnables)
            handlerThread = null;
            handler = null;
        }
        if (thread != null) {
            thread.quitSafely();
            // Bounded wait: a stuck GL task must not block the session teardown forever (the quit thread exits on its own
            // once its queue is drained)
            thread.join(JOIN_TIMEOUT_MS);
            if (thread.isAlive()) {
                Ln.e("OpenGLRunner thread did not terminate within " + JOIN_TIMEOUT_MS + "ms, giving up");
            }
        }
    }

    public Surface start(Size inputSize, Size outputSize, Surface outputSurface) throws OpenGLException {
        initOnce();

        // The whole OpenGL execution must be performed on a Handler, so that SurfaceTexture.setOnFrameAvailableListener() works correctly.
        // See <https://github.com/Genymobile/scrcpy/issues/5444>
        try {
            Threads.executeSynchronouslyOn(handler, new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    run(inputSize, outputSize, outputSurface);
                    return null;
                }
            });
        } catch (Throwable throwable) {
            if (throwable instanceof OpenGLException) {
                throw (OpenGLException) throwable;
            }
            throw new OpenGLException("Asynchronous OpenGL runner init failed", throwable);
        }

        // Synchronization is ok: inputSurface is written before sem.release() and read after sem.acquire()
        return inputSurface;
    }

    private void run(Size inputSize, Size outputSize, Surface outputSurface) throws OpenGLException {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new OpenGLException("Unable to get EGL14 display");
        }

        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw new OpenGLException("Unable to initialize EGL14");
        }

        // @formatter:off
        int[] attribList = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
        };

        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.length, numConfigs, 0);
        if (numConfigs[0] <= 0) {
            EGL14.eglTerminate(eglDisplay);
            throw new OpenGLException("Unable to find ES2 EGL config");
        }
        EGLConfig eglConfig = configs[0];

        // @formatter:off
        int[] contextAttribList = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
        };
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribList, 0);
        if (eglContext == null) {
            EGL14.eglTerminate(eglDisplay);
            throw new OpenGLException("Failed to create EGL context");
        }

        int[] surfaceAttribList = {
                EGL14.EGL_NONE
        };
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, outputSurface, surfaceAttribList, 0);
        if (eglSurface == null) {
            EGL14.eglDestroyContext(eglDisplay, eglContext);
            EGL14.eglTerminate(eglDisplay);
            throw new OpenGLException("Failed to create EGL window surface");
        }

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            EGL14.eglDestroySurface(eglDisplay, eglSurface);
            EGL14.eglDestroyContext(eglDisplay, eglContext);
            EGL14.eglTerminate(eglDisplay);
            throw new OpenGLException("Failed to make EGL context current");
        }

        try {
            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            GLUtils.checkGlError();
            textureId = textures[0];

            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLUtils.checkGlError();
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLUtils.checkGlError();
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLUtils.checkGlError();
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLUtils.checkGlError();

            surfaceTexture = new SurfaceTexture(textureId);
            surfaceTexture.setDefaultBufferSize(inputSize.getWidth(), inputSize.getHeight());
            inputSurface = new Surface(surfaceTexture);

            filter.init();

            surfaceTexture.setOnFrameAvailableListener(surfaceTexture -> {
                if (stopped) {
                    // Make sure to never render after resources have been released
                    return;
                }

                try {
                    render(outputSize);
                } catch (Throwable t) {
                    // In-process hosting: never let an exception escape on this Handler thread, it would kill the host app
                    Ln.e("OpenGL render error", t);
                }
            }, handler);
        } catch (Throwable t) {
            // Clean up the partially initialized GL state: on a start() failure the caller never gets a usable runner,
            // so stopAndRelease() will never be called to release these resources
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(eglDisplay, eglSurface);
            EGL14.eglDestroyContext(eglDisplay, eglContext);
            EGL14.eglTerminate(eglDisplay);
            eglDisplay = EGL14.EGL_NO_DISPLAY;
            eglContext = EGL14.EGL_NO_CONTEXT;
            eglSurface = EGL14.EGL_NO_SURFACE;
            if (surfaceTexture != null) {
                surfaceTexture.release();
                surfaceTexture = null;
            }
            if (inputSurface != null) {
                inputSurface.release();
                inputSurface = null;
            }
            throw t;
        }
    }

    private void render(Size outputSize) {
        GLES20.glViewport(0, 0, outputSize.getWidth(), outputSize.getHeight());
        GLUtils.checkGlError();

        surfaceTexture.updateTexImage();

        float[] matrix;
        if (overrideTransformMatrix != null) {
            matrix = overrideTransformMatrix;
        } else {
            matrix = transformMatrix;
            surfaceTexture.getTransformMatrix(matrix);
        }

        filter.draw(textureId, matrix);

        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, surfaceTexture.getTimestamp());
        EGL14.eglSwapBuffers(eglDisplay, eglSurface);
    }

    public void stopAndRelease() {
        final Semaphore sem = new Semaphore(0);

        // Snapshot the static field under the class monitor (shutdown() resets it under the same lock): an unsynchronized
        // read could observe a stale value, and posting to a quit Looper would silently drop the runnable, making the
        // semaphore wait below block forever
        Handler currentHandler;
        synchronized (OpenGLRunner.class) {
            currentHandler = handler;
        }
        if (currentHandler == null) {
            Ln.w("OpenGLRunner already shut down, GL resources not released");
            return;
        }

        boolean posted = currentHandler.post(() -> {
            try {
                stopped = true;
                // Unregister via the same Handler this runnable runs on (never re-read the static field: shutdown() may
                // have nulled it concurrently)
                surfaceTexture.setOnFrameAvailableListener(null, currentHandler);

                filter.release();

                int[] textures = {textureId};
                GLES20.glDeleteTextures(1, textures, 0);
                GLUtils.checkGlError();

                EGL14.eglDestroySurface(eglDisplay, eglSurface);
                EGL14.eglDestroyContext(eglDisplay, eglContext);
                EGL14.eglTerminate(eglDisplay);
                eglDisplay = EGL14.EGL_NO_DISPLAY;
                eglContext = EGL14.EGL_NO_CONTEXT;
                eglSurface = EGL14.EGL_NO_SURFACE;
                surfaceTexture.release();
                inputSurface.release();
            } catch (Throwable t) {
                // In-process hosting: never let an exception escape on this Handler thread, it would kill the host app
                // (this also covers a partially initialized runner left over by a failed start())
                Ln.e("Could not release OpenGL resources", t);
            } finally {
                // Always release the caller, even on failure, to avoid blocking it forever
                sem.release();
            }
        });

        if (!posted) {
            // The Looper is already gone, the runnable would never run: do not wait on the semaphore forever
            Ln.w("OpenGLRunner thread is shutting down, GL resources not released");
            return;
        }

        try {
            sem.acquire();
        } catch (InterruptedException e) {
            // Behave as if this method call was synchronous
            Thread.currentThread().interrupt();
        }
    }
}
