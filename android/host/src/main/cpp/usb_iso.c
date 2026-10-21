// 等时传输：Android 的 Java USB API 只有控制、bulk、中断三种，采集卡的视频端点却多半是
// 等时的。这里直接对 UsbDeviceConnection 给的 fd 发 usbdevfs ioctl，libusb 在 Linux 上
// 也是这么做的。接口的 claim 和 alternate setting 都已经在 Java 那边做好了。

#include <errno.h>
#include <jni.h>
#include <linux/usbdevice_fs.h>
#include <poll.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>

typedef struct {
    int fd;
    int packet_size;
    int packets;
    int urb_count;
    int in_flight;
    struct usbdevfs_urb **urbs;
    uint8_t *submitted;
} iso_stream;

static struct usbdevfs_urb *alloc_urb(int endpoint, int packets, int packet_size) {
    size_t size = sizeof(struct usbdevfs_urb) + (size_t)packets * sizeof(struct usbdevfs_iso_packet_desc);
    struct usbdevfs_urb *urb = calloc(1, size);
    if (urb == NULL) return NULL;
    urb->buffer = malloc((size_t)packets * packet_size);
    if (urb->buffer == NULL) {
        free(urb);
        return NULL;
    }
    urb->type = USBDEVFS_URB_TYPE_ISO;
    urb->endpoint = (unsigned char)endpoint;
    urb->number_of_packets = packets;
    urb->buffer_length = packets * packet_size;
    return urb;
}

// 内核会改写 status 和各包的 actual_length，每次重新提交前都要复位
static int submit(iso_stream *s, int i) {
    struct usbdevfs_urb *urb = s->urbs[i];
    urb->status = 0;
    urb->actual_length = 0;
    urb->error_count = 0;
    urb->flags = USBDEVFS_URB_ISO_ASAP;
    for (int p = 0; p < s->packets; p++) {
        urb->iso_frame_desc[p].length = s->packet_size;
        urb->iso_frame_desc[p].actual_length = 0;
        urb->iso_frame_desc[p].status = 0;
    }
    if (ioctl(s->fd, USBDEVFS_SUBMITURB, urb) < 0) return -errno;
    s->submitted[i] = 1;
    s->in_flight++;
    return 0;
}

static int index_of(iso_stream *s, struct usbdevfs_urb *urb) {
    for (int i = 0; i < s->urb_count; i++) {
        if (s->urbs[i] == urb) return i;
    }
    return -1;
}

static void destroy(iso_stream *s) {
    for (int i = 0; i < s->urb_count; i++) {
        if (s->urbs[i] != NULL) {
            free(s->urbs[i]->buffer);
            free(s->urbs[i]);
        }
    }
    free(s->urbs);
    free(s->submitted);
    free(s);
}

// 撤回所有在途的 URB 并等它们回来。不等的话内核手里还拿着 URB，
// 下一次在同一个 fd 上 reap 会拿到已经 free 掉的指针。
static void drain(iso_stream *s) {
    for (int i = 0; i < s->urb_count; i++) {
        if (s->submitted[i]) ioctl(s->fd, USBDEVFS_DISCARDURB, s->urbs[i]);
    }
    for (int attempt = 0; s->in_flight > 0 && attempt < 50; attempt++) {
        struct usbdevfs_urb *urb = NULL;
        if (ioctl(s->fd, USBDEVFS_REAPURBNDELAY, &urb) == 0) {
            int i = index_of(s, urb);
            if (i >= 0 && s->submitted[i]) {
                s->submitted[i] = 0;
                s->in_flight--;
            }
            continue;
        }
        if (errno == ENODEV) break;
        struct pollfd p = {.fd = s->fd, .events = POLLOUT};
        poll(&p, 1, 20);
    }
}

JNIEXPORT jlong JNICALL
Java_com_rustkvm_host_uvc_UsbIsoNative_start(JNIEnv *env, jclass clazz, jint fd, jint endpoint,
                                              jint packet_size, jint packets, jint urb_count) {
    (void)env;
    (void)clazz;
    if (packet_size <= 0 || packets <= 0 || urb_count <= 0) return -EINVAL;
    iso_stream *s = calloc(1, sizeof(iso_stream));
    if (s == NULL) return -ENOMEM;
    s->fd = fd;
    s->packet_size = packet_size;
    s->packets = packets;
    s->urb_count = urb_count;
    s->urbs = calloc((size_t)urb_count, sizeof(struct usbdevfs_urb *));
    s->submitted = calloc((size_t)urb_count, 1);
    if (s->urbs == NULL || s->submitted == NULL) {
        destroy(s);
        return -ENOMEM;
    }
    for (int i = 0; i < urb_count; i++) {
        s->urbs[i] = alloc_urb(endpoint, packets, packet_size);
        if (s->urbs[i] == NULL) {
            destroy(s);
            return -ENOMEM;
        }
    }
    for (int i = 0; i < urb_count; i++) {
        int result = submit(s, i);
        if (result < 0) {
            drain(s);
            destroy(s);
            return result;
        }
    }
    return (jlong)(intptr_t)s;
}

// 等一个 URB 回来，把每个包拷进 out（第 i 个包放在 i * packet_size 处，长度写进 lengths[i]，
// 出错的包长度记 0），然后立刻重新提交。返回包数；超时返回 0；设备没了之类返回负的 errno。
JNIEXPORT jint JNICALL
Java_com_rustkvm_host_uvc_UsbIsoNative_reap(JNIEnv *env, jclass clazz, jlong handle, jbyteArray out,
                                             jintArray lengths, jint timeout_ms) {
    (void)clazz;
    iso_stream *s = (iso_stream *)(intptr_t)handle;
    struct usbdevfs_urb *urb = NULL;
    if (ioctl(s->fd, USBDEVFS_REAPURBNDELAY, &urb) < 0) {
        if (errno != EAGAIN) return -errno;
        struct pollfd p = {.fd = s->fd, .events = POLLOUT};
        int ready = poll(&p, 1, timeout_ms);
        if (ready < 0) return errno == EINTR ? 0 : -errno;
        if (ready == 0) return 0;
        if (ioctl(s->fd, USBDEVFS_REAPURBNDELAY, &urb) < 0) {
            if (errno == EAGAIN) return 0;
            return -errno;
        }
    }

    int i = index_of(s, urb);
    if (i < 0) return -EFAULT;
    s->submitted[i] = 0;
    s->in_flight--;

    jsize capacity = (*env)->GetArrayLength(env, out);
    jsize slots = (*env)->GetArrayLength(env, lengths);
    int count = s->packets < slots ? s->packets : slots;
    jint *lens = (*env)->GetIntArrayElements(env, lengths, NULL);
    if (lens == NULL) return -ENOMEM;
    for (int p = 0; p < count; p++) {
        struct usbdevfs_iso_packet_desc *desc = &urb->iso_frame_desc[p];
        int offset = p * s->packet_size;
        int length = (urb->status == 0 && desc->status == 0) ? (int)desc->actual_length : 0;
        if (length > s->packet_size) length = s->packet_size;
        if (offset + length > capacity) length = 0;
        if (length > 0) {
            (*env)->SetByteArrayRegion(env, out, offset, length, (const jbyte *)urb->buffer + offset);
        }
        lens[p] = length;
    }
    (*env)->ReleaseIntArrayElements(env, lengths, lens, 0);

    int status = urb->status;
    int result = submit(s, i);
    if (result < 0) return result;
    // 设备拔掉时 URB 以 -ESHUTDOWN / -ENODEV 回来，重新提交倒不一定立刻失败
    if (status == -ESHUTDOWN || status == -ENODEV) return status;
    return count;
}

JNIEXPORT void JNICALL
Java_com_rustkvm_host_uvc_UsbIsoNative_stop(JNIEnv *env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;
    iso_stream *s = (iso_stream *)(intptr_t)handle;
    if (s == NULL) return;
    drain(s);
    destroy(s);
}
