// Camera 3 (OG0VE) capture-session probe via NDK camera2.
// Goal: determine whether Camera 3 can stream frames through CamX/CameraService
// (bypassing the QSH AON path that crashes the ADSP).
#include <camera/NdkCameraManager.h>
#include <camera/NdkCameraDevice.h>
#include <camera/NdkCameraCaptureSession.h>
#include <media/NdkImageReader.h>
#include <android/native_window.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <unistd.h>
#include <vector>

static volatile int gFrames = 0;
static volatile int gConfigured = 0;
static volatile int gClosed = 0;

static void onDisconnected(void*, ACameraDevice*) { printf("[cam3] device disconnected\n"); }
static void onError(void*, ACameraDevice*, int error) { printf("[cam3] device error=%d\n", error); }
static void onConfigured(void*, ACameraCaptureSession*) { gConfigured = 1; printf("[cam3] session CONFIGURED\n"); }
static void onReady(void*, ACameraCaptureSession*) {}
static void onClosed(void*, ACameraCaptureSession*) { gClosed = 1; printf("[cam3] session closed\n"); }

static void onImage(void* ctx, AImageReader* reader) {
    AImage* img = nullptr;
    if (AImageReader_acquireLatestImage(reader, &img) == AMEDIA_OK && img) {
        gFrames++;
        AImage_delete(img);
    }
}

static const char* fmtName(int32_t f) {
    switch (f) {
        case 0x23: return "YUV_420_888(35)";
        case 0x22: return "PRIVATE(34)";
        case 0x21: return "YUV(33)";
        case 0x20: return "RAW_SENSOR(32)";
        case 0x25: return "RAW10(37)";
        default: return "fmt?";
    }
}

static void trySession(ACameraDevice* dev, int fmt, int w, int h, int seconds) {
    gFrames = 0; gConfigured = 0; gClosed = 0;
    printf("\n[cam3] --- try format=%s %dx%d ---\n", fmtName(fmt), w, h);

    AImageReader* reader = nullptr;
    media_status_t ms = AImageReader_new(w, h, fmt, 4, &reader);
    printf("[cam3] AImageReader_new ms=%d reader=%p\n", ms, reader);
    if (ms != AMEDIA_OK || !reader) return;

    AImageReader_ImageListener listener{nullptr, onImage};
    AImageReader_setImageListener(reader, &listener);

    ANativeWindow* win = nullptr;
    AImageReader_getWindow(reader, &win);

    ACaptureSessionOutputContainer* outputs = nullptr;
    ACaptureSessionOutputContainer_create(&outputs);
    ACaptureSessionOutput* out = nullptr;
    ACaptureSessionOutput_create(win, &out);
    ACaptureSessionOutputContainer_add(outputs, out);

    ACameraCaptureSession* session = nullptr;
    ACameraCaptureSession_stateCallbacks scb{nullptr, onConfigured, onReady, onClosed};
    camera_status_t cs = ACameraDevice_createCaptureSession(dev, outputs, &scb, &session);
    printf("[cam3] createCaptureSession cs=%d session=%p\n", cs, session);

    if (cs == ACAMERA_OK && session) {
        for (int i = 0; i < 30 && !gConfigured; i++) usleep(100 * 1000);
        printf("[cam3] configured=%d\n", gConfigured);
        if (gConfigured) {
            ACaptureRequest* req = nullptr;
            ACameraDevice_createCaptureRequest(dev, TEMPLATE_PREVIEW, &req);
            ACameraOutputTarget* tgt = nullptr;
            ACameraOutputTarget_create(win, &tgt);
            ACaptureRequest_addTarget(req, tgt);
            ACameraCaptureSession_setRepeatingRequest(session, nullptr, 1, &req, nullptr);
            for (int i = 0; i < seconds * 10; i++) usleep(100 * 1000);
            printf("[cam3] frames=%d after %ds\n", gFrames, seconds);
            ACameraOutputTarget_free(tgt);
            ACaptureRequest_free(req);
        }
        ACameraCaptureSession_close(session);
    }

    ACaptureSessionOutputContainer_free(outputs);
    ACaptureSessionOutput_free(out);
    AImageReader_delete(reader);
}

int main(int argc, char** argv) {
    const char* id = argc > 1 ? argv[1] : "3";
    int seconds = argc > 2 ? atoi(argv[2]) : 3;

    ACameraManager* cm = ACameraManager_create();
    ACameraIdList* ids = nullptr;
    ACameraManager_getCameraIdList(cm, &ids);
    printf("[cam3] camera count=%d\n", ids ? ids->numCameras : -1);
    if (ids) for (int i = 0; i < ids->numCameras; i++) printf("  [%d] id=%s\n", i, ids->cameraIds[i]);

    ACameraDevice* dev = nullptr;
    ACameraDevice_StateCallbacks dcb{nullptr, onDisconnected, onError};
    camera_status_t cs = ACameraManager_openCamera(cm, id, &dcb, &dev);
    printf("[cam3] openCamera(%s) cs=%d dev=%p\n", id, cs, dev);
    if (cs != ACAMERA_OK || !dev) { printf("[cam3] cannot open\n"); return 1; }

    // Sweep the advertised formats/sizes for this mono ULP sensor.
    trySession(dev, 0x23, 640, 480, seconds); // YUV_420_888
    trySession(dev, 0x22, 640, 480, seconds); // PRIVATE
    trySession(dev, 0x21, 640, 480, seconds); // 33
    trySession(dev, 0x36, 640, 480, seconds); // 54
    trySession(dev, 0x20, 640, 480, seconds); // RAW_SENSOR

    ACameraDevice_close(dev);
    ACameraManager_delete(cm);
    printf("[cam3] done\n");
    return 0;
}
