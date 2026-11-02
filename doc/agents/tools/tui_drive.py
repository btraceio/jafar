"""Drive a full-screen TUI through a pseudo-terminal.

Usage: tui_drive.py <out-dir> <cmd...> -- <line1> <line2> ...
Types each line + Enter, waits for the screen to settle, and writes the screen
text captured after each line to <out-dir>/<n>.txt (ANSI stripped, rendered
through a minimal VT100 screen model so cursor-addressed output reads as rows).
"""
import os, pty, re, select, struct, sys, time, fcntl, termios

ROWS, COLS = 60, 200


class Screen:
    def __init__(self):
        self.rows = [[" "] * COLS for _ in range(ROWS)]
        self.r = self.c = 0

    def feed(self, data):
        i = 0
        while i < len(data):
            ch = data[i]
            if ch == "\x1b":
                m = re.match(r"\x1b\[([?0-9;]*)([A-Za-z@`])", data[i:])
                if m:
                    self.csi(m.group(1), m.group(2))
                    i += m.end()
                    continue
                m = re.match(r"\x1b\][^\x07\x1b]*(\x07|\x1b\\)", data[i:])
                if m:
                    i += m.end()
                    continue
                i += 2
                continue
            if ch == "\r":
                self.c = 0
            elif ch == "\n":
                self.r = min(ROWS - 1, self.r + 1)
            elif ch == "\b":
                self.c = max(0, self.c - 1)
            elif ch >= " ":
                if self.c < COLS:
                    self.rows[self.r][self.c] = ch
                self.c += 1
            i += 1

    def csi(self, params, cmd):
        nums = [int(x) if x.isdigit() else 0 for x in params.lstrip("?").split(";")] if params else []
        n = nums[0] if nums and nums[0] else 1
        if cmd in "Hf":
            self.r = (nums[0] if len(nums) > 0 and nums[0] else 1) - 1
            self.c = (nums[1] if len(nums) > 1 and nums[1] else 1) - 1
        elif cmd == "A":
            self.r = max(0, self.r - n)
        elif cmd == "B":
            self.r = min(ROWS - 1, self.r + n)
        elif cmd == "C":
            self.c = min(COLS - 1, self.c + n)
        elif cmd == "D":
            self.c = max(0, self.c - n)
        elif cmd == "G":
            self.c = n - 1
        elif cmd == "d":
            self.r = n - 1
        elif cmd == "J":
            mode = nums[0] if nums else 0
            if mode == 2 or mode == 3:
                self.rows = [[" "] * COLS for _ in range(ROWS)]
            elif mode == 0:
                for c in range(self.c, COLS):
                    self.rows[self.r][c] = " "
                for r in range(self.r + 1, ROWS):
                    self.rows[r] = [" "] * COLS
        elif cmd == "K":
            mode = nums[0] if nums else 0
            rng = range(self.c, COLS) if mode == 0 else range(0, self.c + 1) if mode == 1 else range(COLS)
            for c in rng:
                self.rows[self.r][c] = " "
        elif cmd == "X":
            for c in range(self.c, min(COLS, self.c + n)):
                self.rows[self.r][c] = " "
        self.r = max(0, min(ROWS - 1, self.r))
        self.c = max(0, min(COLS, self.c))

    def text(self):
        return "\n".join("".join(r).rstrip() for r in self.rows).rstrip() + "\n"


def main():
    out = sys.argv[1]
    sep = sys.argv.index("--")
    cmd, lines = sys.argv[2:sep], sys.argv[sep + 1 :]
    pid, fd = pty.fork()
    if pid == 0:
        os.environ["TERM"] = "xterm-256color"
        os.execvp(cmd[0], cmd)
    fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("HHHH", ROWS, COLS, 0, 0))
    screen = Screen()

    def pump(settle, limit):
        end = time.time() + limit
        last = time.time()
        while time.time() < end:
            r, _, _ = select.select([fd], [], [], 0.2)
            if r:
                try:
                    data = os.read(fd, 65536)
                except OSError:
                    return False
                if not data:
                    return False
                screen.feed(data.decode("utf-8", "replace"))
                last = time.time()
            elif time.time() - last > settle:
                return True
        return True

    pump(3.0, 60)  # startup, file open
    for n, line in enumerate(lines, 1):
        for ch in line:
            os.write(fd, ch.encode())
            time.sleep(0.01)
        os.write(fd, b"\r")
        pump(3.0, 90)
        with open(os.path.join(out, f"{n}.txt"), "w") as f:
            f.write(f"### {line}\n" + screen.text())
    try:
        os.write(fd, b"\x03")
        time.sleep(0.5)
        os.kill(pid, 9)
    except Exception:
        pass


main()
