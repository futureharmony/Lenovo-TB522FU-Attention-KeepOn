// AON HAL probe: raw libbinder_ndk client for vendor.qti.hardware.camera.aon.IAONService
// Wire format reverse-engineered from vendor.qti.hardware.camera.aon-V3-ndk.so
#include <dlfcn.h>
#include <android/binder_ibinder.h>
#include <android/binder_parcel.h>
#include <android/binder_status.h>
static AIBinder* (*pGetService)(const char*);
#define AServiceManager_getService pGetService
#include <android/binder_ibinder.h>
#include <android/binder_parcel.h>
#include <android/binder_status.h>
#include <cstdio>
#include <cstring>
#include <unistd.h>
#include <string>
#include <vector>
#include <camera/NdkCameraManager.h>
#include <camera/NdkCameraDevice.h>
#include <camera/NdkCameraCaptureSession.h>
#include <media/NdkImageReader.h>

#include <sys/stat.h>
#include <sys/file.h>
#include <fcntl.h>

static void onDiscCb(void*, ACameraDevice*) {}
static const char* SVC_IFACE = "vendor.qti.hardware.camera.aon.IAONService";
static const char* SVC_INST  = "vendor.qti.hardware.camera.aon.IAONService/default";
static const char* CB_DESC  = "vendor.qti.hardware.camera.aon.IAONServiceCallback";

#define LOG(...) do { printf("[AON] " __VA_ARGS__); printf("\n"); fflush(stdout); } while(0)

static AIBinder_Class* gSvcClass;
static AIBinder_Class* gCbClass;
static AIBinder* gCbBinder;
static FILE* gEvtFile = nullptr;
static std::string gEvtPath;

static volatile bool gReporting = false;
static char gLastEvtBuf[1024] = {0};
static int gLastEvtCount = 0;
static long gLastEvtTime = 0;
static volatile long gLastHalEvtMs = 0;   // monotonic ms of last real HAL callback
static volatile long gLastRealEvtMs = 0;  // monotonic ms of last REAL callback (never touched by the synthetic-absent refresh; drives recovery)
static volatile int gEvtMask = 0;         // FDPro EvtTypeMask of the last parsed event
static volatile int gEvtGaze = 0;         // FDPro isGazeDetected flag of the last parsed event
static volatile long gLastGazeMs = 0;     // monotonic ms of the last event reporting an active gaze
static volatile sig_atomic_t gExitRequested = 0;
static long gLastRecoveryMs = 0;          // monotonic ms of last provider-restart recovery
static long gLastKeepaliveMs = 0;         // monotonic ms of last gaze userActivity ping
static long gRecoveryTimes[8] = {0};      // mono ms of recent recoveries (failure-rate tracking)
static int gRecoveryIdx = 0;
static long gCooldownUntilMs = 0;         // crash-loop cooldown: no registration attempts before this
static bool gLastCmdWasStart = false;     // re-register after a cooldown ends

static void onSigTerm(int) {
    gExitRequested = 1;
}

static long monoMs() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000L + ts.tv_nsec / 1000000L;
}

#define EVT_MAX_BYTES (512 * 1024)
static void evtAppend(const char* fmt, ...) {
    if (gEvtFile) {
        struct stat fst;
        if (fstat(fileno(gEvtFile), &fst) == 0 && !gEvtPath.empty()) {
            bool unlinked = (fst.st_nlink == 0);
            bool oversized = (fst.st_size > EVT_MAX_BYTES);
            if (unlinked || oversized) {
                fclose(gEvtFile);
                gEvtFile = fopen(gEvtPath.c_str(), oversized ? "w" : "a");
                if (gEvtFile) setvbuf(gEvtFile, nullptr, _IOLBF, 0);
            }
        }
    }
    if (!gEvtFile) return;
    va_list ap; va_start(ap, fmt);
    vfprintf(gEvtFile, fmt, ap);
    va_end(ap);
    fprintf(gEvtFile, "\n");
    fflush(gEvtFile);
}

// ---------------- callback (server side) ----------------
struct CbData { int x; };
static void* cbOnCreate(void*) { return new CbData{}; }
static void cbOnDestroy(void* d) { delete (CbData*)d; }
static volatile int gDumpParcel = 0;   // set by daemon arg or -d flag: dump raw parcel ints
static binder_status_t cbOnTransact(AIBinder*, uint32_t code, const AParcel* in, AParcel* out) {
    if (code != 1) { LOG("cb code=%u (ignored)", code); return STATUS_UNKNOWN_TRANSACTION; }
    LOG("cb transact code=%u", code);
    // Path-2 diagnostics: dump the raw incoming parcel (first 40 int32 slots)
    // BEFORE parsing, so we can see exactly what the HAL sent and verify the
    // line-format parser is not dropping data. Rewind after.
    if (gDumpParcel) {
        int32_t startPos = AParcel_getDataPosition(in);
        char raw[2048]; int off = 0;
        for (int n = 0; n < 40; n++) {
            int32_t v;
            if (AParcel_readInt32(in, &v) != STATUS_OK) break;
            off += snprintf(raw + off, sizeof(raw) - off, "%s%d", n ? "," : "", v);
        }
        LOG("RAW[%d]: %s", startPos, raw);
        if (gReporting) evtAppend("RAW[%d]: %s", startPos, raw);
        AParcel_setDataPosition(in, startPos);
    }
    int64_t clientId = 0;
    if (AParcel_readInt64(in, &clientId) != STATUS_OK) return STATUS_FAILED_TRANSACTION;

    gLastHalEvtMs = monoMs(); // any callback (present or absent) counts as a live HAL event
    gLastRealEvtMs = gLastHalEvtMs;

    int32_t pres = 0;
    if (AParcel_readInt32(in, &pres) != STATUS_OK || !pres) {
        LOG("EVT pres=0");
        if (gReporting) {
            evtAppend("EVT pres=0");
        }
        AStatus* ok = AStatus_newOk();
        binder_status_t st = AParcel_writeStatusHeader(out, ok);
        if (st == STATUS_OK) AParcel_writeInt32(out, 0); // NotifyAONCallbackEvent returns int
        return st;
    }
    int32_t len = 0, serviceType = 0;
    AParcel_readInt32(in, &len);
    AParcel_readInt32(in, &serviceType);
    // f1 is the second header field after serviceType (RAW dump verified:
    // [pres][len][f0=serviceType][f1]). Skipping it misaligns the entire
    // 5-blob loop and silently drops every FDPro payload.
    int32_t f1 = 0;
    AParcel_readInt32(in, &f1);

    for (int b = 0; b < 5; b++) {
        int32_t l = -1;
        if (AParcel_readInt32(in, &l) != STATUS_OK) break;
        if (l <= 0) continue;
        int32_t v; int nread = 0;
        char buf[1024]; int off = 0;
        while (nread * 4 < l && AParcel_readInt32(in, &v) == STATUS_OK) {
            if (b == 1 && off < 900) {
                off += snprintf(buf + off, sizeof(buf) - off, "%s%d", nread ? "," : "", v);
            }
            if (b == 1 && nread == 0) { gEvtMask = v; gEvtGaze = 0; }
            if (b == 1 && nread == 16) gEvtGaze = v;
            nread++;
        }
        if (b == 1) {
            snprintf(gLastEvtBuf, sizeof(gLastEvtBuf), "%s", buf);
            gLastEvtCount = l / 4;
            gLastEvtTime = time(nullptr);
            LOG("EVT FDPro n=%d vals=%s", l / 4, buf);
            if ((gEvtMask & 0x4) || gEvtGaze) gLastGazeMs = monoMs();
            if (gReporting) {
                evtAppend("EVT b=1 n=%d vals=%s", l / 4, buf);
            }
        }
    }

    AStatus* ok = AStatus_newOk();
    binder_status_t st = AParcel_writeStatusHeader(out, ok);
    if (st == STATUS_OK) AParcel_writeInt32(out, 0); // NotifyAONCallbackEvent returns int
    return st;
}
static binder_status_t dumpCb(AIBinder*, uint32_t, const AParcel*, AParcel*) { return STATUS_OK; }

// ---------------- helpers ----------------
static void dumpParcel(AParcel* p) {
    int32_t v; int n = 0;
    while (n < 256) {
        binder_status_t st = AParcel_readInt32(p, &v);
        if (st != STATUS_OK) break;
        float f; memcpy(&f, &v, 4);
        LOG("  [%d] %d (float %g)", n, v, f);
        n++;
    }
    if (n == 0) LOG("  <empty>");
}

static binder_status_t onSvcDump(AIBinder*, uint32_t, const AParcel*, AParcel*) { return STATUS_OK; }
static void* onSvcCreate(void*) { return nullptr; }
static void onSvcDestroy(void*) {}

static int classic_main(int argc, char** argv) {
    void* h = dlopen("libbinder_ndk.so", RTLD_NOW | RTLD_GLOBAL);
    if (!h) { LOG("dlopen libbinder_ndk: %s", dlerror()); return 1; }
    pGetService = (AIBinder* (*)(const char*))dlsym(h, "AServiceManager_getService");
    if (!pGetService) pGetService = (AIBinder* (*)(const char*))dlsym(h, "AServiceManager_checkService");
    if (!pGetService) { LOG("no getService sym"); return 1; }
    gSvcClass = AIBinder_Class_define(SVC_IFACE, onSvcCreate, onSvcDestroy, onSvcDump);
    gCbClass  = AIBinder_Class_define(CB_DESC, cbOnCreate, cbOnDestroy, cbOnTransact);
    if (!gSvcClass || !gCbClass) { LOG("class define failed"); return 1; }

    // Optional warmup: briefly open camera 3 so CamX loads the OG0VE sensor
    // mode table (AONCam handshake needs SensorMode data), then close it.
    if (argc >= 2 && strcmp(argv[1], "-w") == 0) {
        ACameraManager* cm = ACameraManager_create();
        ACameraDevice* dev = nullptr;
        ACameraDevice_StateCallbacks cb{nullptr, onDiscCb};
        camera_status_t cs = ACameraManager_openCamera(cm, "3", &cb, &dev);
        LOG("warmup open camera3 cs=%d dev=%p", cs, dev);
        if (dev) { usleep(2500*1000); ACameraDevice_close(dev); LOG("warmup closed"); }
        ACameraManager_delete(cm);
        argv++; argc--;
    }

    AIBinder* svc = AServiceManager_getService(SVC_INST);
    LOG("svc=%p assoc=%d", svc, AIBinder_associateClass(svc, gSvcClass));
    if (!svc) return 1;

    // ---- 1: GetAONSensorInfoList ----
    {
        AParcel* in = nullptr; AParcel* out = nullptr;
        binder_status_t st = AIBinder_prepareTransaction(svc, &in);
        LOG("prepare st=%d", st);
        st = AIBinder_transact(svc, 1 /*GetAONSensorInfoList*/, &in, &out, 0);
        LOG("transact(1) st=%d out=%p", st, out);
        if (st == STATUS_OK && out) {
            AStatus* status = nullptr;
            AParcel_readStatusHeader(out, &status);
            LOG("status ok=%d", status ? AStatus_isOk(status) : -1);
            if (status && AStatus_isOk(status)) {
                LOG("reply ints:");
                dumpParcel(out);
            } else if (status) {
                LOG("status desc: %s", AStatus_getDescription(status));
            }
        }
        if (out) AParcel_delete(out);
    }

    // ---- 2: RegisterClient (args: serviceType sensorIdx w h deliveryPerSec evtMask waitMs) ----
    long clientId = -1;
    if (argc >= 7) {
        int32_t aonCamIdx = atoi(argv[1]), st_ = atoi(argv[2]);
        int32_t w = atoi(argv[3]), h = atoi(argv[4]);
        int32_t dps = atoi(argv[5]), mask = (int32_t)strtoul(argv[6], nullptr, 0);
        int waitMs = argc > 8 ? atoi(argv[8]) : 10000;

        gCbBinder = AIBinder_new(gCbClass, nullptr);
        static bool (*markVintf)(AIBinder*) = (bool (*)(AIBinder*))dlsym(RTLD_DEFAULT, "AIBinder_markVintfStability");
        LOG("cb binder=%p markVintf=%p ret=%d", gCbBinder, markVintf, markVintf ? markVintf(gCbBinder) : -1);
        static void (*setPoolMax)(int) = (void (*)(int))dlsym(RTLD_DEFAULT, "ABinderProcess_setThreadPoolMaxThreadCount");
        LOG("setPoolMax=%p -> %d", setPoolMax, setPoolMax ? (setPoolMax(4), 4) : -1);
        // MUST start the binder thread pool BEFORE RegisterClient: the HAL starts
        // delivering AON events the moment registration succeeds, and with no pool
        // running every incoming callback is dropped ("Thread Pool max thread count
        // is 0" / unhandled transactions).
        static void* (*startPool)() = (void* (*)())dlsym(RTLD_DEFAULT, "ABinderProcess_startThreadPool");
        if (startPool) { startPool(); LOG("binder thread pool started BEFORE register"); }

        AParcel* in = nullptr; AParcel* out = nullptr;
        binder_status_t st = AIBinder_prepareTransaction(svc, &in);
        // args: (callback binder, AONRegisterInfo)
        AParcel_writeStrongBinder(in, gCbBinder);
        AParcel_writeInt32(in, 1); // AONRegisterInfo presence
        int32_t hdrPos = AParcel_getDataPosition(in);
        AParcel_writeInt32(in, 0); // length placeholder
        AParcel_writeInt32(in, aonCamIdx); // aonCamIdx
        AParcel_writeInt32(in, st_);   // AONServiceType (0=FD,1=FDPRO,2=QR,3=HD,4=GD)

        int32_t algo = argc > 9 ? atoi(argv[9]) : 0;
        int32_t deliveryMode = 1;
        int32_t deliveryPeriodMs = (dps > 0) ? (1000 / dps) : 66;
        // FDRegisterInfo: mask, algoModeIdx, deliveryMode(1=periodic), deliveryPeriodMs(>0), deliveryPerSec
        int32_t blk[5] = {mask, algo, deliveryMode, deliveryPeriodMs, dps};
        // choose branch: FD for type 0/1, QR for 2, HD for 3, GD for 4
        const int branchIdx = (st_ <= 1) ? 0 : (st_ - 2); // 0=FD 1=QR 2=HD 3=GD
        for (int b = 0; b < 4; b++) {
            if (b == branchIdx) {
                AParcel_writeInt32(in, 1); // present
                int32_t p0 = AParcel_getDataPosition(in);
                AParcel_writeInt32(in, 0); // len placeholder
                AParcel_writeInt32(in, blk[0]);
                AParcel_writeInt32(in, blk[1]);
                AParcel_writeInt32(in, blk[2]);
                AParcel_writeInt32(in, blk[3]);
                AParcel_writeInt32(in, blk[4]);
                int32_t p1 = AParcel_getDataPosition(in);
                AParcel_setDataPosition(in, p0); AParcel_writeInt32(in, p1); AParcel_setDataPosition(in, p1);
            } else {
                AParcel_writeInt32(in, 0); // absent
            }
        }
        {
            int32_t endPos = AParcel_getDataPosition(in);
            AParcel_setDataPosition(in, hdrPos); AParcel_writeInt32(in, endPos); AParcel_setDataPosition(in, endPos);
        }

        st = AIBinder_transact(svc, 2 /*RegisterClient*/, &in, &out, 0);
        LOG("transact(2 RegisterClient) st=%d", st);
        int regOk = 0; long regCid = -1;
        if (st == STATUS_OK && out) {
            AStatus* status = nullptr;
            AParcel_readStatusHeader(out, &status);
            if (status && AStatus_isOk(status)) {
                int64_t cid = 0;
                AParcel_readInt64(out, &cid);
                clientId = cid;
                regOk = 1; regCid = (long)cid;
                LOG("clientId=%ld", (long)cid);
            } else if (status) {
                LOG("status desc: %s", AStatus_getDescription(status));
            }
        }
        if (out) AParcel_delete(out);

        // self-test: oneway transact to our own callback binder
        {
            AParcel* tin = nullptr; AParcel* tout = nullptr;
            AIBinder_prepareTransaction(gCbBinder, &tin);
            AParcel_writeInt64(tin, 12345);
            binder_status_t tst = AIBinder_transact(gCbBinder, 1, &tin, &tout, 1 /*ONEWAY*/);
            LOG("self oneway to callback st=%d", tst);
            if (tout) AParcel_delete(tout);
            usleep(300*1000);
        }
        LOG("waiting %d ms for events...", waitMs);
        static void* (*joinPool)() = (void* (*)())dlsym(RTLD_DEFAULT, "ABinderProcess_joinThreadPool");
        for (int i = 0; i < waitMs / 500; i++) usleep(500 * 1000);
    }

    // ---- 3: UnregisterClient ----
    if (clientId >= 0) {
        AParcel* in = nullptr; AParcel* out = nullptr;
        AIBinder_prepareTransaction(svc, &in);
        AParcel_writeInt64(in, clientId);
        binder_status_t st = AIBinder_transact(svc, 3 /*UnregisterClient*/, &in, &out, 0);
        LOG("transact(3 UnregisterClient) st=%d", st);
        if (out) AParcel_delete(out);
    }

    LOG("done");
    return 0;
}


static AIBinder_DeathRecipient* gDeathRecipient = nullptr;
static AIBinder* gCurrentSvc = nullptr;
static long gCurrentClientId = -1;

static void onServiceDied(void* /*cookie*/) {
    LOG("IAONService binder died!");
    evtAppend("SVC died");
    gCurrentSvc = nullptr;
    gCurrentClientId = -1;
}

static bool gCamera3WarmedUp = false;
static void warmupCamera3() {
    if (gCamera3WarmedUp) return;
    ACameraManager* cm = ACameraManager_create();
    if (cm) {
        ACameraDevice* dev = nullptr;
        ACameraDevice_StateCallbacks cb{nullptr, onDiscCb};
        camera_status_t cs = ACameraManager_openCamera(cm, "3", &cb, &dev);
        if (dev) {
            usleep(1500 * 1000);
            ACameraDevice_close(dev);
            gCamera3WarmedUp = true;
        }
        ACameraManager_delete(cm);
    }
}

// reuse the register path: returns 0 on success (clientId in *outCid)
static int daemonRegister(const char* svcInst, int camIdx, int srvType, int mask,
        int algo, int w, int h, int dps, long* outCid, AIBinder** outSvc) {
    warmupCamera3();

    *outSvc = AServiceManager_getService(svcInst);
    if (!*outSvc) { evtAppend("REG ok=0 err=no_service"); return -1; }
    AIBinder_associateClass(*outSvc, gSvcClass);

    if (!gDeathRecipient) {
        gDeathRecipient = AIBinder_DeathRecipient_new(onServiceDied);
    }
    if (gDeathRecipient) {
        AIBinder_linkToDeath(*outSvc, gDeathRecipient, nullptr);
    }

    int32_t deliveryMode = 1;
    int32_t deliveryPeriodMs = (dps > 0) ? (1000 / dps) : 66;

    AParcel* in = nullptr; AParcel* out = nullptr;
    AIBinder_prepareTransaction(*outSvc, &in);
    AParcel_writeStrongBinder(in, gCbBinder);
    AParcel_writeInt32(in, 1);           // AONRegisterInfo present
    int32_t hdr = AParcel_getDataPosition(in);
    AParcel_writeInt32(in, 0);           // len placeholder
    AParcel_writeInt32(in, camIdx);
    AParcel_writeInt32(in, srvType);
    AParcel_writeInt32(in, 1);           // FD present
    int32_t fd = AParcel_getDataPosition(in);
    AParcel_writeInt32(in, 0);
    AParcel_writeInt32(in, mask);
    AParcel_writeInt32(in, algo);
    AParcel_writeInt32(in, deliveryMode);
    AParcel_writeInt32(in, deliveryPeriodMs);
    AParcel_writeInt32(in, dps);
    int32_t fdEnd = AParcel_getDataPosition(in);
    AParcel_setDataPosition(in, fd); AParcel_writeInt32(in, fdEnd); AParcel_setDataPosition(in, fdEnd);
    AParcel_writeInt32(in, 0);           // QR absent
    AParcel_writeInt32(in, 0);           // HD absent
    AParcel_writeInt32(in, 0);           // GD absent
    int32_t endPos = AParcel_getDataPosition(in);
    AParcel_setDataPosition(in, hdr); AParcel_writeInt32(in, endPos); AParcel_setDataPosition(in, endPos);

    binder_status_t st = AIBinder_transact(*outSvc, 2, &in, &out, 0);
    if (st != STATUS_OK || !out) { evtAppend("REG ok=0 err=transact_%d", st); return -1; }
    AStatus* status = nullptr;
    AParcel_readStatusHeader(out, &status);
    if (!status || !AStatus_isOk(status)) { evtAppend("REG ok=0 err=status"); AParcel_delete(out); return -1; }
    int64_t cid = 0; AParcel_readInt64(out, &cid);
    AParcel_delete(out);
    *outCid = (long)cid;
    gCurrentSvc = *outSvc;
    gCurrentClientId = (long)cid;
    evtAppend("REG ok=1 cid=%ld", (long)cid);
    return 0;
}

static void daemonUnregister(AIBinder* svc, long cid) {
    if (!svc || cid < 0) return;
    if (gDeathRecipient) {
        AIBinder_unlinkToDeath(svc, gDeathRecipient, nullptr);
    }
    AParcel* in = nullptr; AParcel* out = nullptr;
    AIBinder_prepareTransaction(svc, &in);
    AParcel_writeInt64(in, cid);
    AIBinder_transact(svc, 3, &in, &out, 0);
    if (out) AParcel_delete(out);
    evtAppend("REG ok=-1 stop cid=%ld", cid);
    gCurrentSvc = nullptr;
    gCurrentClientId = -1;
}

// strip the placeholder + self-test from classic path by early-exiting before them
static int daemon_main(int argc, char** argv) {
    // --daemon <cmdFile> <evtFile> <camIdx> <srvType> <mask> <algo> <w> <h> <dps>
    if (argc < 11) { printf("usage: --daemon <cmd> <evt> <camIdx> <srv> <mask> <algo> <w> <h> <dps>\n"); return 1; }
    const char* cmdFile = argv[2];
    const char* evtFile = argv[3];
    int camIdx = atoi(argv[4]), srvType = atoi(argv[5]), mask = (int)strtoul(argv[6], nullptr, 0);
    int algo = atoi(argv[7]), w = atoi(argv[8]), h = atoi(argv[9]), dps = atoi(argv[10]);
    (void)argc; (void)argv;

    // 1. Singleton lock to prevent duplicate concurrent daemons
    int lockFd = open("/data/adb/tb522fu_attention/aon.lock", O_CREAT | O_RDWR, 0666);
    if (lockFd >= 0) {
        if (flock(lockFd, LOCK_EX | LOCK_NB) != 0) {
            printf("[AON] Another daemon instance already holds lock, exiting cleanly.\n");
            return 0;
        }
    }

    // 2. Self OOM Score Adj (-1000)
    FILE* oom = fopen("/proc/self/oom_score_adj", "w");
    if (oom) { fputs("-1000\n", oom); fclose(oom); }

    // 3. Migrate to root cgroups to escape any inherited init service cgroup
    pid_t myPid = getpid();
    for (const char* cgPath : {"/sys/fs/cgroup/cgroup.procs", "/dev/cpuset/cgroup.procs", "/sys/fs/cgroup/freezer/cgroup.procs"}) {
        FILE* cg = fopen(cgPath, "w");
        if (cg) { fprintf(cg, "%d\n", myPid); fclose(cg); }
    }

    // 4. Record PID file for precise watchdog tracking
    FILE* pf = fopen("/data/adb/tb522fu_attention/aon.pid", "w");
    if (pf) { fprintf(pf, "%d\n", myPid); fclose(pf); }

    gEvtPath = evtFile;
    gEvtFile = fopen(evtFile, "w"); // truncate: never replay events from a previous session
    if (!gEvtFile) { printf("cannot open evt file\n"); return 1; }
    setvbuf(gEvtFile, nullptr, _IOLBF, 0);
    evtAppend("DAEMON up pid=%d", myPid);

    signal(SIGTERM, onSigTerm);
    signal(SIGINT, onSigTerm);

    // Camera 3 warmup is deferred to the first "start" (daemonRegister) so that an
    // idle/disabled daemon never touches the camera or the DSP power rails at boot.

    void* hBinder = dlopen("libbinder_ndk.so", RTLD_NOW | RTLD_GLOBAL);
    if (!hBinder) hBinder = dlopen("/system/lib64/libbinder_ndk.so", RTLD_NOW | RTLD_GLOBAL);
    pGetService = (AIBinder* (*)(const char*))dlsym(hBinder ? hBinder : RTLD_DEFAULT, "AServiceManager_getService");
    if (!pGetService) pGetService = (AIBinder* (*)(const char*))dlsym(hBinder ? hBinder : RTLD_DEFAULT, "AServiceManager_checkService");

    gSvcClass = AIBinder_Class_define("vendor.qti.hardware.camera.aon.IAONService",
            onSvcCreate, onSvcDestroy, onSvcDump);
    gCbClass  = AIBinder_Class_define("vendor.qti.hardware.camera.aon.IAONServiceCallback",
            cbOnCreate, cbOnDestroy, cbOnTransact);
    gCbBinder = AIBinder_new(gCbClass, nullptr);
    bool (*markVintf)(AIBinder*) = (bool (*)(AIBinder*))dlsym(hBinder ? hBinder : RTLD_DEFAULT, "AIBinder_markVintfStability");
    if (markVintf && gCbBinder) markVintf(gCbBinder);
    if (!gSvcClass || !gCbClass || !gCbBinder) { evtAppend("DAEMON err=class"); return 1; }

    void (*setPoolMax)(uint32_t) = (void (*)(uint32_t))dlsym(hBinder ? hBinder : RTLD_DEFAULT, "ABinderProcess_setThreadPoolMaxThreadCount");
    void (*startPool)() = (void (*)())dlsym(hBinder ? hBinder : RTLD_DEFAULT, "ABinderProcess_startThreadPool");
    LOG("daemon binder: hBinder=%p setPoolMax=%p startPool=%p", hBinder, setPoolMax, startPool);
    if (setPoolMax) setPoolMax(4);
    if (startPool) startPool();

    enum State { IDLE, ACTIVE } state = IDLE;
    AIBinder* svc = nullptr;
    long clientId = -1;
    char line[192];
    long lastSeq = -1;
    long lastHbt = 0;
    // --selftest-gaze <sec>: inject synthetic FDPRO gaze events (byte-identical
    // to what the real HAL sends, README §17) into our own callback, so the
    // keep-on glue (gaze event -> KEEPALIVE -> userActivity) can be exercised
    // without a human face. Requires a real registration via state=start.
    long selftestEndMs = 0, lastSelfTestMs = 0;
    for (int i = 2; i < argc - 1; i++) {
        if (strcmp(argv[i], "--selftest-gaze") == 0) selftestEndMs = atol(argv[i + 1]) * 1000L;
    }
    gLastHalEvtMs = monoMs();
    gLastRealEvtMs = gLastHalEvtMs;
    while (!gExitRequested) {
        line[0] = 0;
        FILE* f = fopen(cmdFile, "r");
        if (f) {
            if (fgets(line, sizeof line, f)) {
                line[strcspn(line, "\r\n")] = 0;
            }
            fclose(f);
        }

        // State-based IPC: the file always holds the latest desired state.
        // seq is monotonic (elapsedRealtime on the Java side) so a restarted
        // client still outranks the previous session's last command.
        long seq = -1;
        const char* sp = strstr(line, "seq=");
        if (sp) seq = atol(sp + 4);
        if (line[0] && seq > lastSeq) {
            lastSeq = seq;
            if (strncmp(line, "state=exit", 10) == 0) {
                if (svc && clientId >= 0) {
                    daemonUnregister(svc, clientId);
                    AIBinder_decStrong(svc);
                    svc = nullptr; clientId = -1;
                }
                break;
            } else if (strncmp(line, "state=stop", 10) == 0) {
                if (svc && clientId >= 0) {
                    daemonUnregister(svc, clientId);
                    AIBinder_decStrong(svc);
                    svc = nullptr; clientId = -1;
                }
                state = IDLE;
                gReporting = false;
                evtAppend("STOPPED");
            } else if (strncmp(line, "state=start", 11) == 0) {
                int c = -1, s_ = -1, m = -1, a = -1, ww = -1, hh = -1, d = -1;
                if (sscanf(line + 11, "%d %d %d %d %d %d %d", &c, &s_, &m, &a, &ww, &hh, &d) == 7) {
                    bool changed = (camIdx != c || srvType != s_ || mask != m
                            || algo != a || w != ww || h != hh || dps != d);
                    camIdx = c; srvType = s_; mask = m; algo = a; w = ww; h = hh; dps = d;
                    if (changed && clientId >= 0 && svc) {
                        daemonUnregister(svc, clientId);
                        AIBinder_decStrong(svc);
                        svc = nullptr; clientId = -1; state = IDLE; gReporting = false;
                        usleep(300 * 1000);
                    }
                }
                evtAppend("STARTING");
                if (clientId < 0 || !svc) {
                    if (daemonRegister("vendor.qti.hardware.camera.aon.IAONService/default",
                            camIdx, srvType, mask, algo, w, h, dps, &clientId, &svc) == 0) {
                        state = ACTIVE;
                        gReporting = true;
                    } else {
                        svc = nullptr; clientId = -1; state = IDLE; gReporting = false;
                    }
                } else {
                    state = ACTIVE;
                    gReporting = true;
                    evtAppend("REG ok=1 cid=%ld (active)", clientId);
                    if (gLastEvtTime > 0 && time(nullptr) - gLastEvtTime < 3 && gLastEvtCount > 0) {
                        evtAppend("EVT b=1 n=%d vals=%s", gLastEvtCount, gLastEvtBuf);
                    }
                }
            }
        }

        // Sync with service death if occurred
        if (gCurrentClientId < 0 && clientId >= 0) {
            clientId = -1;
            svc = nullptr;
            state = IDLE;
            gReporting = false;
        }

        // Synthetic absent: if the HAL is silent for >1.5s we are the ones who
        // must keep the event stream fresh, otherwise the Java side waits out
        // its 4s deadline and reports ATTENTION_FAILURE_TIMED_OUT (Error=5).
        long nowMs = monoMs();
        // SSC death detection: the QSEE-AON SSC connection can die mid-stream
        // ("SensorErrorCallback ... SSC connectionError"), and repeated
        // register/unregister cycles can poison the QSH AonCam session so that
        // even algo=2 starts ADSP-crash-looping (AonCam_0:0x10000014, one SSR
        // every ~6s, USB re-enumerating with it). Recovery: unregister first
        // (an unregistered session stops feeding the crash), restart the
        // provider, WAIT for the ADSP to go quiet, then re-register. If
        // recoveries keep failing, cool down: unregister and stay idle —
        // registering into a poisoned session only perpetuates the crash.
        if (gReporting && state == ACTIVE && nowMs - gLastRealEvtMs > 8000 &&
                nowMs - gLastRecoveryMs > 60000) {
            gLastRecoveryMs = nowMs;
            evtAppend("RECOVERY begin ssc_silent_ms=%d", (int)(nowMs - gLastRealEvtMs));
            gReporting = false;
            if (svc && clientId >= 0) {
                daemonUnregister(svc, clientId);
                AIBinder_decStrong(svc);
                svc = nullptr; clientId = -1;
            }
            state = IDLE;
            int recent = 0;
            for (int k = 0; k < 8; k++)
                if (gRecoveryTimes[k] && nowMs - gRecoveryTimes[k] < 300000) recent++;
            if (recent >= 3) {
                gCooldownUntilMs = nowMs + 180000;
                memset(gRecoveryTimes, 0, sizeof gRecoveryTimes);
                evtAppend("RECOVERY cooldown enter 3_failures_per_5min until_ms=%ld", gCooldownUntilMs);
            } else if (nowMs < gCooldownUntilMs) {
                evtAppend("RECOVERY skipped cooldown");
            } else {
                gRecoveryTimes[gRecoveryIdx++ % 8] = nowMs;
                int rc = system("killall vendor.qti.camera.provider-service_64");
                evtAppend("RECOVERY killall rc=%d", rc);
                bool back = false;
                for (int i = 0; i < 60; i++) {
                    usleep(500 * 1000);
                    AIBinder* t = AServiceManager_getService(SVC_INST);
                    if (t) { AIBinder_decStrong(t); back = true; break; }
                }
                evtAppend("RECOVERY service_back=%d", (int)back);
                usleep(6000 * 1000);   // let the fresh CamX finish sensor probing
                // Wait for the ADSP to stop crashing before re-registering —
                // a poisoned session crashes on registration, and re-registering
                // into it would resume the SSR/USB flap loop.
                int quietLoops = 0;
                for (int i = 0; i < 12; i++) {
                    FILE* p = popen("dmesg 2>/dev/null | tail -40 | grep -c 0x10000014", "r");
                    int cnt = -1;
                    if (p) { char buf[16]; if (fgets(buf, sizeof buf, p)) cnt = atoi(buf); pclose(p); }
                    if (cnt == 0) break;
                    quietLoops++;
                    evtAppend("RECOVERY adsp_busy=%d waiting", cnt);
                    usleep(2000 * 1000);
                }
                evtAppend("RECOVERY quiet=%d", quietLoops == 0);
                gCamera3WarmedUp = false;  // camera3 open must re-handoff to the AON pipeline
                if (daemonRegister(SVC_INST, camIdx, srvType, mask, algo, w, h, dps, &clientId, &svc) == 0) {
                    state = ACTIVE;
                    gReporting = true;
                    gLastHalEvtMs = monoMs();
                    gLastRealEvtMs = gLastHalEvtMs;   // grace period before the next recovery check
                    evtAppend("RECOVERY re-registered cid=%ld", clientId);
                } else {
                    svc = nullptr; clientId = -1; state = IDLE;
                    evtAppend("RECOVERY re-register failed");
                }
            }
        }
        // Cooldown expired while the user still wants sensing: re-register once.
        if (!gReporting && state == IDLE && gLastCmdWasStart &&
                gCooldownUntilMs && nowMs > gCooldownUntilMs) {
            gCooldownUntilMs = 0;
            evtAppend("RECOVERY cooldown exit, re-registering");
            if (daemonRegister(SVC_INST, camIdx, srvType, mask, algo, w, h, dps, &clientId, &svc) == 0) {
                state = ACTIVE;
                gReporting = true;
                gLastHalEvtMs = monoMs();
                gLastRealEvtMs = gLastHalEvtMs;
                evtAppend("REG ok=1 cid=%ld (cooldown exit)", clientId);
            }
        }
        if (gReporting && state == ACTIVE && nowMs - gLastHalEvtMs > 1500) {
            evtAppend("EVT pres=0");
            gLastHalEvtMs = nowMs;
        }

        if (selftestEndMs > 0 && state == ACTIVE && gReporting) {
            if (lastSelfTestMs == 0) {
                lastSelfTestMs = nowMs;
                selftestEndMs += nowMs;   // anchor the window from first ACTIVE tick
            }
            if (nowMs < selftestEndMs && nowMs - lastSelfTestMs >= 2000) {
                lastSelfTestMs = nowMs;
                AParcel* tin = nullptr; AParcel* tout = nullptr;
                AIBinder_prepareTransaction(gCbBinder, &tin);
                AParcel_writeInt64(tin, 1);      // clientId
                AParcel_writeInt32(tin, 1);      // pres
                AParcel_writeInt32(tin, 116);    // len
                AParcel_writeInt32(tin, 1);      // f0 = FDPRO
                AParcel_writeInt32(tin, 1);      // f1
                AParcel_writeInt32(tin, 8); AParcel_writeInt32(tin, 0); AParcel_writeInt32(tin, 1);
                static const int32_t pro[18] = {5,1,60,480,360,1,1,40,0,0,112,150,1,12,247,257,1,1};
                AParcel_writeInt32(tin, 72);
                for (int k = 0; k < 18; k++) AParcel_writeInt32(tin, pro[k]);
                AParcel_writeInt32(tin, 8); AParcel_writeInt32(tin, 0); AParcel_writeInt32(tin, 0);
                AParcel_writeInt32(tin, 0);      // HD absent
                AParcel_writeInt32(tin, 0);      // GD absent
                AIBinder_transact(gCbBinder, 1, &tin, &tout, 1 /*ONEWAY*/);
                if (tout) AParcel_delete(tout);
                evtAppend("SELFTEST gaze event injected");
            }
        }

        // Gaze keep-on: while the FDPRO stream reports an active gaze, refresh
        // the screen timeout with PowerManager.userActivity (transaction 14).
        // Throttled to one call per 3s; each call buys one screen-timeout
        // window (~10s on this ROM). Type 4 (ATTENTION) is silently dropped by
        // PowerManagerService when no attention service is configured, so use
        // type 0 (OTHER). userActivity never wakes a sleeping display, so a
        // stale ACTIVE session cannot keep the screen on unattended.
        if (gReporting && state == ACTIVE && nowMs - gLastGazeMs < 4000 &&
                nowMs - gLastKeepaliveMs >= 3000) {
            gLastKeepaliveMs = nowMs;
            struct timespec bt;
            clock_gettime(CLOCK_BOOTTIME, &bt);
            long bootMs = bt.tv_sec * 1000L + bt.tv_nsec / 1000000L;
            char cmd[128];
            snprintf(cmd, sizeof cmd,
                    "service call power 14 i32 0 i64 %ld i32 0 i32 0 >/dev/null 2>&1",
                    bootMs);
            int rc = system(cmd);
            if (rc == 0) evtAppend("KEEPALIVE ping boot=%ld", bootMs);
            else evtAppend("KEEPALIVE failed rc=%d", rc);
        }

        long now = time(nullptr);
        if (now - lastHbt >= 5) {
            evtAppend("HBT %ld st=%d rep=%d cid=%ld", now, (int)state, (int)gReporting, clientId);
            lastHbt = now;
        }
        usleep(250 * 1000);
    }

    if (svc && clientId >= 0) {
        daemonUnregister(svc, clientId);
        AIBinder_decStrong(svc);
        svc = nullptr; clientId = -1;
    }
    evtAppend("DAEMON down");
    unlink("/data/adb/tb522fu_attention/aon.pid");
    fclose(gEvtFile);
    return 0;
}

int main(int argc, char** argv) {
    if (getenv("AON_DUMP_PARCEL")) gDumpParcel = 1;
    if (argc >= 2 && strcmp(argv[1], "--daemon") == 0) return daemon_main(argc, argv);
    return classic_main(argc, argv);
}
