// dm_framegen.h's implementation — Banana Native's vka_framegen.c over this repo's structures. The differences
// from the original are structural only: the window texture arrives as the replay's own vkimg_t (no registry
// acquire — the one queue's submission order and the 60-present eviction window keep it alive through the
// chain's read, and a destroy would be deferred past the GPU anyway); the submissions carry no timeline wait
// (same queue, same order); the generated frames are plain images the present thread blits itself. GPL headers
// and the status vocabulary stay Banana's.
#include "dm_framegen.h"
#include "vka.h"
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#ifdef NX_LSFG
#include "lsfg/lsfg.h"
#endif

static _Atomic int g_on = -1, g_mult = 2, g_target, g_flow = 75, g_state;   // g_state: 0 off, 1 generating, 2 on and not generating, -1 unavailable
static char g_reason[256] = "off";
static pthread_mutex_t g_rmu = PTHREAD_MUTEX_INITIALIZER;
// The host's frame rate limit (the adaptive rate, perf_android.c): the rate's own strong definition wins this weak one; 0 = no limit here
__attribute__((weak)) int dm_perf_rate_fps(void) { return 0; }
static void reason(const char *fmt, const char *a) { pthread_mutex_lock(&g_rmu); snprintf(g_reason, sizeof g_reason, fmt, a); pthread_mutex_unlock(&g_rmu); }

static void settings_init(void) {
    int exp = -1;
    if (atomic_load(&g_on) != -1) return;
    const char *e;
    if ((e = getenv("DM_LSFG_MULT"))) atomic_store(&g_mult, atoi(e));
    if ((e = getenv("DM_LSFG_TARGET"))) atomic_store(&g_target, atoi(e));
    if ((e = getenv("DM_LSFG_FLOW"))) atomic_store(&g_flow, atoi(e));
    e = getenv("DM_LSFG");
    atomic_compare_exchange_strong(&g_on, &exp, e && atoi(e) ? 1 : 0);
}

void dm_fg_set(int on, int multiplier, int target_rate, int flow_pct) {
    atomic_store(&g_mult, multiplier < 2 ? 2 : multiplier > 4 ? 4 : multiplier);
    atomic_store(&g_target, target_rate < 0 ? 0 : target_rate);
    atomic_store(&g_flow, flow_pct <= 0 ? 0 : flow_pct < 25 ? 25 : flow_pct > 100 ? 100 : flow_pct);
    atomic_store(&g_on, on ? 1 : 0);
    VLOG("frame generation: %s, x%d, target %d fps, flow %d%%", on ? "on" : "off", atomic_load(&g_mult), atomic_load(&g_target), atomic_load(&g_flow));
}

int dm_fg_status(char *buf, int n) {
    settings_init();
    int st = atomic_load(&g_state);
    if (buf && n > 0) { pthread_mutex_lock(&g_rmu); snprintf(buf, (size_t)n, "%s", g_reason); pthread_mutex_unlock(&g_rmu); }
    return st;
}

#ifndef NX_LSFG
int dm_fg_frame(vkimg_t *im, const int *crop, double period, VkImage gen[3], double *hold, double *out_period) {
    (void)im; (void)crop; (void)period; (void)gen;
    settings_init(); *hold = 0; *out_period = 0;
    if (atomic_load(&g_on) > 0) { atomic_store(&g_state, -1); reason("%s", "not in this build (built without NX_LSFG)"); }
    return 0;
}
#else
enum { NFG = 3 };
static struct {
    int state;                         // 0 not tried, 1 ready (modules loaded), -1 unavailable
    lsfg_modules mods;
    lsfg_chain *chain; VkExtent2D ext; int flow;
    VkImage out[LSFG_MAX_GENERATIONS]; VkImageView out_view[LSFG_MAX_GENERATIONS]; VkDeviceMemory out_mem[LSFG_MAX_GENERATIONS];
    VkCommandPool pool; struct { VkCommandBuffer cb; VkFence fence; } fr[NFG]; unsigned fi;
    lsfg_pacer pacer; uint64_t count; uint32_t warm_streak;
    double out_period, longer_since;   // the output period the window's frame rate is set for, and since when a longer one is wanted
} F;

static double mono_s(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec + t.tv_nsec / 1e9; }
static void unavailable(const char *why, const char *a) { F.state = -1; reason(why, a); atomic_store(&g_state, -1); VRED("frame generation unavailable: %s", g_reason); }

// The device must have what the shaders use (Float16, StorageImageExtendedFormats, StorageImageWriteWithoutFormat), null descriptors, and storage images of the chain's formats
static int device_ok(void) {
    VkPhysicalDeviceVulkan12Features f12 = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES};
    VkPhysicalDeviceFeatures2 f2 = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2, .pNext = &f12};
    vk.vkGetPhysicalDeviceFeatures2(V.pd, &f2);
    const char *miss = !f12.shaderFloat16 ? "shaderFloat16" : !f2.features.shaderStorageImageExtendedFormats ? "shaderStorageImageExtendedFormats"
                     : !f2.features.shaderStorageImageWriteWithoutFormat ? "shaderStorageImageWriteWithoutFormat" : !V.null_desc ? "null descriptors (robustness2)" : NULL;
    VkFormat fmts[3] = {VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_R8_UNORM, VK_FORMAT_R16G16B16A16_SFLOAT};
    for (int i = 0; i < 3 && !miss; i++) {
        VkFormatProperties fp; vk.vkGetPhysicalDeviceFormatProperties(V.pd, fmts[i], &fp);
        if (!(fp.optimalTilingFeatures & VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT) || !(fp.optimalTilingFeatures & VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT)) miss = "storage images of R8, RGBA8 or RGBA16F";
    }
    if (miss) { unavailable("the GPU lacks %s", miss); return 0; }
    return 1;
}

static int load(void) {
    if (F.state) return F.state > 0;
    if (!device_ok()) return 0;
    const char *path = getenv("DM_LSFG_DLL");
    if (!path || !*path) { unavailable("%s", "no Lossless.dll (the app looks in Documents/Emulation/bios, Switch and Download, or pick one on the settings page)"); return 0; }
    double t0 = mono_s();
    int r = lsfg_dll_load(path, &F.mods);
    if (r) { char b[200]; snprintf(b, sizeof b, "%s: %s", path, lsfg_status_name(r)); unavailable("%s", b); return 0; }
    VLOG("frame generation: %d shaders from %s (%.1f ms)", F.mods.n, path, (mono_s() - t0) * 1e3);
    F.state = 1;
    return 1;
}

static void teardown(void) {
    if (!F.chain && !F.out[0]) return;
    pthread_mutex_lock(V.qmu); vk.vkQueueWaitIdle(V.q); pthread_mutex_unlock(V.qmu);
    lsfg_chain_free(F.chain); F.chain = NULL;
    for (int i = 0; i < LSFG_MAX_GENERATIONS; i++) {
        if (F.out_view[i]) vk.vkDestroyImageView(V.dev, F.out_view[i], NULL);
        F.out_view[i] = VK_NULL_HANDLE;
        if (F.out[i]) vk.vkDestroyImage(V.dev, F.out[i], NULL);
        F.out[i] = VK_NULL_HANDLE;
        if (F.out_mem[i]) vk.vkFreeMemory(V.dev, F.out_mem[i], NULL);
        F.out_mem[i] = VK_NULL_HANDLE;
    }
    lsfg_pacer_reset(&F.pacer); F.count = 0; F.warm_streak = 0; F.out_period = 0; F.longer_since = 0;
}

// A generated frame's own image (the present blits it into the swapchain as it blits the game's)
static int out_new(VkExtent2D e, int i, VkImageView *view) {
    VkImageCreateInfo ci = {VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, .imageType = VK_IMAGE_TYPE_2D, .format = VK_FORMAT_R8G8B8A8_UNORM,
        .extent = {e.width, e.height, 1}, .mipLevels = 1, .arrayLayers = 1, .samples = VK_SAMPLE_COUNT_1_BIT, .tiling = VK_IMAGE_TILING_OPTIMAL,
        .usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT};
    VkResult r = vk.vkCreateImage(V.dev, &ci, NULL, &F.out[i]);
    VkMemoryRequirements mr = {0}; if (!r) vk.vkGetImageMemoryRequirements(V.dev, F.out[i], &mr);
    VkMemoryAllocateInfo ai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, .allocationSize = mr.size, .memoryTypeIndex = vka_mem_type(mr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0)};
    if (!r && ai.memoryTypeIndex == UINT32_MAX) r = VK_ERROR_OUT_OF_DEVICE_MEMORY;
    if (!r) r = vk.vkAllocateMemory(V.dev, &ai, NULL, &F.out_mem[i]);
    if (!r) r = vk.vkBindImageMemory(V.dev, F.out[i], F.out_mem[i], 0);
    VkImageViewCreateInfo vi = {VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO, .image = F.out[i], .viewType = VK_IMAGE_VIEW_TYPE_2D, .format = VK_FORMAT_R8G8B8A8_UNORM, .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}};
    if (!r) r = vk.vkCreateImageView(V.dev, &vi, NULL, view);
    if (r) { VRED("frame generation: output image %ux%u: %d", e.width, e.height, r); F.out[i] = VK_NULL_HANDLE; return 0; }
    return 1;
}

static int rebuild(VkExtent2D e, int flow) {
    teardown();
    VkImage imgs[LSFG_MAX_GENERATIONS];
    for (int i = 0; i < LSFG_MAX_GENERATIONS; i++) { if (!out_new(e, i, &F.out_view[i])) { teardown(); return 0; } imgs[i] = F.out[i]; }
    double t0 = mono_s();
    F.chain = lsfg_chain_new(&F.mods, e, flow ? flow / 100.0f : 1.0f, imgs, F.out_view);
    if (!F.chain) { teardown(); return 0; }
    F.ext = e; F.flow = flow;
    VLOG("frame generation: chain for %ux%u made in %.1f ms", e.width, e.height, (mono_s() - t0) * 1e3);
    return 1;
}

static void full_barrier(VkCommandBuffer cb) {   // the game's writes to the window texture, and the presents still reading the outputs (earlier submissions)
    VkMemoryBarrier mb = {VK_STRUCTURE_TYPE_MEMORY_BARRIER, NULL, VK_ACCESS_MEMORY_WRITE_BIT | VK_ACCESS_MEMORY_READ_BIT, VK_ACCESS_MEMORY_WRITE_BIT | VK_ACCESS_MEMORY_READ_BIT};
    vk.vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 1, &mb, 0, NULL, 0, NULL);
}

int dm_fg_frame(vkimg_t *im, const int *crop, double period, VkImage gen[3], double *hold, double *out_period) {
    settings_init();
    *hold = 0; *out_period = 0;
    int on = atomic_load(&g_on) > 0, mult = atomic_load(&g_mult), target = atomic_load(&g_target), flow = atomic_load(&g_flow);
    if (!on) {
        if (F.chain) { teardown(); VLOG("frame generation off"); }
        if (F.state >= 0) { atomic_store(&g_state, 0); reason("%s", "off"); }
        return 0;
    }
    if (!im || !im->img) return 0;
    if (!load()) return 0;
    // The game's frame period is never shorter than the host's own frame limit allows (the adaptive rate, perf_android.c): a game that asks for interval 1
    // (16.7 ms) but is held to 30 fps has two refreshes of a 60 Hz panel a frame, room for one generated frame, and its output rate is 60, not 120
    { int lim = dm_perf_rate_fps(); if (lim > 0 && period < 1.0 / lim) period = 1.0 / lim; }
    // The window's frame rate: a shorter output period at once, a longer one only after it held for 2 s (a game that switches its own present
    // interval would flip the panel's mode back and forth; the generated frames are paced by hold, not by the panel's rate)
    double want = target > 0 ? 1.0 / target : period / (mult < 2 ? 2 : mult > 4 ? 4 : mult), tnow = mono_s();
    if (F.out_period <= 0 || want < F.out_period - 1e-4) { F.out_period = want; F.longer_since = 0; }
    else if (want > F.out_period + 1e-4) { if (!F.longer_since) F.longer_since = tnow; else if (tnow - F.longer_since >= 2.0) { F.out_period = want; F.longer_since = 0; } }
    else F.longer_since = 0;
    *out_period = F.out_period;
    int cx = 0, cy = 0, cw = (int)im->w, ch = (int)im->h;
    if (crop && crop[2] > 0 && crop[3] > 0) { cx = crop[0]; cy = crop[1]; cw = crop[2]; ch = crop[3]; }
    VkFormatProperties fp = {0}; vk.vkGetPhysicalDeviceFormatProperties(V.pd, im->fmt, &fp);
    if (!im->img || !(fp.optimalTilingFeatures & VK_FORMAT_FEATURE_BLIT_SRC_BIT) || cw <= 0 || ch <= 0) {
        VKRED_ONCE(96, "frame generation: the window texture (format %d) cannot be copied into the chain: not generating", im->fmt);
        F.warm_streak = 0; atomic_store(&g_state, 2); return 0;
    }
    VkExtent2D e = {(uint32_t)cw, (uint32_t)ch};
    if (!F.chain || F.ext.width != e.width || F.ext.height != e.height || F.flow != flow) {
        if (!rebuild(e, flow)) { unavailable("%s", "the frame generation chain could not be made (see the [RED] lines)"); return 0; }
    }
    // The floor for the game's own rate (DM_LSFG_MIN_FPS, default 30): the generation's work must not push the game under it
    F.pacer.min_base = 30.0f;
    { const char *e = getenv("DM_LSFG_MIN_FPS"); if (e && atoi(e) > 0) F.pacer.min_base = (float)atoi(e); }
    // Decoupled (the pacer's other measure of the game's own rate): a game whose speed follows its measured rate keeps its speed at any rate, so a
    // slower base does not slow it down. Every game here is held by a limiter or by its own engine, so a slower base slows it too: not decoupled.
    F.pacer.decoupled = 0;
    lsfg_plan plan = lsfg_pacer_plan(&F.pacer, LSFG_MAX_GENERATIONS, mult, target, mono_s());
    uint64_t count = F.count++;
    int warm = plan.warm && count + 1 >= 2;
    F.warm_streak = warm ? F.warm_streak + 1 : 0;
    size_t ng = warm && F.warm_streak >= 2 ? plan.generations : 0;
    if (ng > LSFG_MAX_GENERATIONS) ng = LSFG_MAX_GENERATIONS;
    // Generated frames only as many as the refreshes in the game's own period leave room for (the panel shows one image a refresh; more would hold
    // the FIFO present, and the game with it, to a fraction of its rate — Banana's Honor phone note). DM_VK_FG_REFRESH_CAP=0: no cap
    uint64_t rns = getenv("DM_VK_FG_REFRESH_CAP") && !atoi(getenv("DM_VK_FG_REFRESH_CAP")) ? 0 : vk_refresh_cycle_ns(); int capped = 0;
    if (ng && rns && period > 0) {
        // The game's frame period: the requested one (the limit's, above) or the measured one when longer (a throttled game's frames are further apart)
        double gp = F.pacer.smoothed_interval > period ? F.pacer.smoothed_interval : period;
        double n = gp * 1e9 / (double)rns;   // the refreshes in a game frame
        size_t fit = n < 1.9 ? 0 : (size_t)(n + 0.1) - 1;
        if (ng > fit) { ng = fit; capped = 1; F.pacer.issued = ng; }
    }

    unsigned k = F.fi++ % NFG;
    if (!F.fr[k].cb) {
        if (!F.pool) { VkCommandPoolCreateInfo pi = {VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO, .flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT, .queueFamilyIndex = V.qf}; vk.vkCreateCommandPool(V.dev, &pi, NULL, &F.pool); }
        VkCommandBufferAllocateInfo ai = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO, .commandPool = F.pool, .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY, .commandBufferCount = 1};
        VkFenceCreateInfo fi = {VK_STRUCTURE_TYPE_FENCE_CREATE_INFO, .flags = VK_FENCE_CREATE_SIGNALED_BIT};
        vk.vkAllocateCommandBuffers(V.dev, &ai, &F.fr[k].cb); vk.vkCreateFence(V.dev, &fi, NULL, &F.fr[k].fence);
    }
    vk.vkWaitForFences(V.dev, 1, &F.fr[k].fence, VK_TRUE, UINT64_MAX);
    vk.vkResetFences(V.dev, 1, &F.fr[k].fence);
    VkCommandBuffer cb = F.fr[k].cb; vk.vkResetCommandBuffer(cb, 0);
    VkCommandBufferBeginInfo bi = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO, .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT};
    vk.vkBeginCommandBuffer(cb, &bi);
    full_barrier(cb);
    VkImageBlit b = {{VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}, {{cx, cy, 0}, {cx + cw, cy + ch, 1}}, {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}, {{0, 0, 0}, {cw, ch, 1}}};
    if (vk_window_bottom_up()) { b.srcOffsets[0].y = cy + ch; b.srcOffsets[1].y = cy; }   // lower-left origin: the window image is stored bottom-up, the chain sees it upright
    vk.vkCmdBlitImage(cb, im->img, VK_IMAGE_LAYOUT_GENERAL, lsfg_chain_input(F.chain, count), VK_IMAGE_LAYOUT_GENERAL, 1, &b, VK_FILTER_NEAREST);
    if (warm) lsfg_chain_shared(F.chain, cb, count);
    for (size_t g = 0; g < ng; g++) lsfg_chain_generate(F.chain, cb, count, ng, g);
    full_barrier(cb);   // the outputs for the presents after this
    vk.vkEndCommandBuffer(cb);
    VkSubmitInfo si = {VK_STRUCTURE_TYPE_SUBMIT_INFO, NULL, 0, NULL, NULL, 1, &cb};
    pthread_mutex_lock(V.qmu); VkResult r = vk.vkQueueSubmit(V.q, 1, &si, F.fr[k].fence); pthread_mutex_unlock(V.qmu);
    if (r) { static _Atomic int n; if (atomic_fetch_add(&n, 1) < 8) VRED("frame generation submission %d", r); ng = 0; }
    for (size_t g = 0; g < ng; g++) gen[g] = F.out[g];
    double interval = F.pacer.smoothed_interval > 0 ? F.pacer.smoothed_interval : period;
    if (interval < period) interval = period;
    if (ng) *hold = interval / (double)(ng + 1);
    // A hold within 0.15 refresh of a whole number of refreshes is that number: the measured interval runs a little long (34 ms at a 30 fps frame step), and a hold
    // of 17 ms on a 16.7 ms panel let each image's desired time (the previous one's plus the hold) drift across a refresh every few dozen frames, two images in one
    // refresh
    if (ng && rns) { double r = *hold * 1e9 / (double)rns, w = floor(r + 0.5); if (w >= 1 && fabs(r - w) <= 0.15) *hold = w * (double)rns / 1e9; }
    int st2 = ng ? 1 : 2;
    // The target rate is met: there is no frame to add (the game already runs at the target, or faster)
    int met = target > 0 && F.pacer.smoothed_interval > 0 && 1.0 / F.pacer.smoothed_interval >= target * 0.95;
    static int was_capped, was_met;
    if (atomic_exchange(&g_state, st2) != st2 || (st2 == 2 && capped != was_capped) || (st2 == 2 && met != was_met)) {
        char why[96]; snprintf(why, sizeof why, "on, the display runs at %.0f Hz: no refresh left for a generated frame", rns ? 1e9 / (double)rns : 0.0);
        reason("%s", st2 == 1 ? "generating" : capped ? why : met ? "on, the target rate is met: no frame to add" : "on, waiting for a steady frame rate");
        static int ncap; if (st2 == 2 && capped && ncap++ < 4) VLOG("frame generation: not generating, %s (game frame period %.1f ms)", why + 4, period * 1e3);
    }
    was_capped = st2 == 2 && capped;
    was_met = st2 == 2 && met;
    return (int)ng;
}
#endif
