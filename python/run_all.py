#!/usr/bin/env python3
"""
Launcher for the whole ablation benchmark. The experiments themselves are Java client classes
(ExpPruning, ExpOrder, ExpTraversal, ExpScalability, and the opt-in ExpEfficiency and ExpLazy in src/); this script compiles the Java sources, writes
results/system_info.txt (including the CPU name, which plain Java cannot report), and starts the four experiments
one after another. Results are appended to results/results.csv.

    python run_all.py --mode smoke      # 2-3 minutes: checks that everything works
    python run_all.py --mode quick      # about 10-20 minutes
    python run_all.py --mode full       # hours; Ctrl+C is safe, re-run the same command to resume
    python run_all.py --mode full --heap 8192 --timeout 1800 --axes all   # EVERYTHING, including the opt-in efficiency and lazy experiments
    python run_all.py --mode quick --axes efficiency   # compares the efficiency values of all variants (writes results/efficiency.csv)

Options (all optional): --reps N  --timeout SECONDS  --heap MB  --datasets chess mushroom ...  --axes pruning order ...
                        --out DIR  --java PATH_TO_JAVA
You can also run one experiment directly, e.g.:  java -cp out ExpPruning --mode quick
Only the Python standard library is needed here.
"""
import argparse, os, platform, subprocess, sys, time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src")
OUT = os.path.join(ROOT, "out")
EXPERIMENTS = {"pruning": "ExpPruning", "order": "ExpOrder", "traversal": "ExpTraversal", "scalability": "ExpScalability",
               "efficiency": "ExpEfficiency", "lazy": "ExpLazy"}
DEFAULT_AXES = ["pruning", "order", "traversal", "scalability"]   # efficiency and lazy are opt-in (python run_all.py --axes lazy)


def compile_java(javac):
    """Compiles src/*.java into out/ when the classes are missing or older than the sources."""
    marker = os.path.join(OUT, "ExperimentBase.class")
    srcs = [os.path.join(SRC, f) for f in os.listdir(SRC) if f.endswith(".java")]
    if os.path.exists(marker) and all(os.path.getmtime(s) <= os.path.getmtime(marker) for s in srcs):
        return
    os.makedirs(OUT, exist_ok=True)
    print("Compiling Java sources ...", flush=True)
    r = subprocess.run([javac, "-d", OUT] + srcs, capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stderr)
        sys.exit("javac failed - is a JDK (not just a JRE) installed and on the PATH?")


def system_info(args, java):
    """Returns the text of system_info.txt: OS, CPU, RAM, Java version and the run settings."""
    lines = ["MAEI ablation benchmark - system information", "=" * 50,
             "date: " + time.strftime("%Y-%m-%d %H:%M:%S"),
             "mode: %s   reps: %s   timeout: %ss   heap: %s MB" % (args.mode, args.reps or "default", args.timeout or "default", args.heap),
             "os: " + platform.platform(), "machine: " + platform.machine(),
             "processor: " + platform.processor(), "logical cpus: %s" % os.cpu_count(),
             "python: " + platform.python_version()]
    try:
        r = subprocess.run([java, "-version"], capture_output=True, text=True)
        lines.append("java: " + (r.stderr or r.stdout).strip().replace("\n", " | "))
    except Exception as e:
        lines.append("java: unknown (%s)" % e)
    ram = None
    try:
        if os.path.exists("/proc/meminfo"):
            ram = int(open("/proc/meminfo").readline().split()[1]) // 1024
        elif platform.system() == "Windows":
            ps = lambda q: subprocess.run(["powershell", "-NoProfile", "-Command", q], capture_output=True, text=True).stdout.strip()
            ram = int(ps("(Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory")) // (1024 * 1024)
            lines.append("cpu name: " + ps("(Get-CimInstance Win32_Processor).Name"))
        elif platform.system() == "Darwin":
            sysctl = lambda k: subprocess.run(["sysctl", "-n", k], capture_output=True, text=True).stdout.strip()
            ram = int(sysctl("hw.memsize")) // (1024 * 1024)
            lines.append("cpu name: " + sysctl("machdep.cpu.brand_string"))
    except Exception:
        pass
    lines.append("ram (MB): %s" % (ram if ram else "unknown"))
    lines.append("")
    lines.append("Tips used for stable timing: plugged in, other programs closed, nothing else running in parallel.")
    return "\n".join(lines)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", choices=["smoke", "quick", "full"], default="quick")
    ap.add_argument("--reps", type=int, default=None)
    ap.add_argument("--timeout", type=int, default=None)
    ap.add_argument("--heap", type=int, default=4096, help="JVM max heap in MB for every run")
    ap.add_argument("--datasets", nargs="*", default=None)
    ap.add_argument("--axes", nargs="*", default=DEFAULT_AXES, choices=list(EXPERIMENTS) + ["all"],
                    help="experiments to run; \"all\" = every experiment, including the opt-in efficiency and lazy ones")
    ap.add_argument("--out", default=os.path.join(ROOT, "results"))
    ap.add_argument("--java", default="java", help="path to the java executable")
    args = ap.parse_args()
    if "all" in args.axes:
        args.axes = list(EXPERIMENTS)

    javac = os.path.join(os.path.dirname(args.java), "javac") if os.path.dirname(args.java) else "javac"
    compile_java(javac)
    os.makedirs(args.out, exist_ok=True)
    info = os.path.join(args.out, "system_info.txt")
    with open(info, "w") as f:
        f.write(system_info(args, args.java) + "\n")

    common = ["--mode", args.mode, "--heap", str(args.heap), "--out", args.out, "--root", ROOT]
    if args.reps: common += ["--reps", str(args.reps)]
    if args.timeout: common += ["--timeout", str(args.timeout)]
    if args.datasets: common += ["--datasets"] + args.datasets
    for axis in args.axes:
        print("\n=== experiment: %s (%s) ===" % (axis, EXPERIMENTS[axis]), flush=True)
        r = subprocess.run([args.java, "-cp", OUT, EXPERIMENTS[axis]] + common)
        if r.returncode != 0:
            sys.exit("experiment %s failed (exit code %d)" % (axis, r.returncode))
    print("\nALL DONE. Results in", os.path.join(args.out, "results.csv"))


if __name__ == "__main__":
    main()
