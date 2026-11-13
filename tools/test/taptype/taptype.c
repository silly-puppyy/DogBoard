// taptype: plays taps into a Linux multi-touch device (benchmark helper, emulator only).
//   taptype /dev/input/event2 < taps.txt
// Each input line: raw_x raw_y press_ms gap_ms  (device coordinates; hold, then wait after lift).
// Every touch gets a new tracking id (some keyboards ignore a repeated one).
#include <fcntl.h>
#include <linux/input.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

static int fd;

static void emit(int type, int code, int value) {
    struct input_event ev;
    memset(&ev, 0, sizeof ev);
    ev.type = type;
    ev.code = code;
    ev.value = value;
    write(fd, &ev, sizeof ev);
}

static void sleep_ms(int ms) {
    struct timespec t = {ms / 1000, (long)(ms % 1000) * 1000000L};
    nanosleep(&t, NULL);
}

int main(int argc, char **argv) {
    if (argc < 2 || (fd = open(argv[1], O_WRONLY)) < 0) {
        perror("open");
        return 1;
    }
    int id = (int)(time(NULL) & 0xffff) * 16, x, y, press, gap;
    while (scanf("%d %d %d %d", &x, &y, &press, &gap) == 4) {
        emit(EV_ABS, ABS_MT_SLOT, 0);
        emit(EV_ABS, ABS_MT_TRACKING_ID, ++id);
        emit(EV_ABS, ABS_MT_POSITION_X, x);
        emit(EV_ABS, ABS_MT_POSITION_Y, y);
        emit(EV_ABS, ABS_MT_PRESSURE, 50);
        emit(EV_KEY, BTN_TOUCH, 1);
        emit(EV_SYN, SYN_REPORT, 0);
        sleep_ms(press);
        emit(EV_ABS, ABS_MT_TRACKING_ID, -1);
        emit(EV_KEY, BTN_TOUCH, 0);
        emit(EV_SYN, SYN_REPORT, 0);
        sleep_ms(gap);
    }
    return 0;
}
