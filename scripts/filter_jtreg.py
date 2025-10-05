#!/usr/bin/env python3
"""
filter_jtreg_seeds.py

Keep only jtreg Java files that compile and run successfully by themselves.

Pipeline:
  1) Scan for *.java that textually contain 'static void main(' (leave comments intact).
  2) Copy each candidate to OUT_ROOT (preserve structure by default).
  3) Compile JDK's test library (test/lib) once into a temp classes dir.
  4) For each candidate, compile it **together with sibling sources** in the same directory,
     using CP = (compiled testlib) + (user --classpath if given).
  5) Run every top-level class emitted; if any returns exit code 0, KEEP the copied .java.
     Otherwise DELETE the copied .java. Always delete compiled classes.
  6) Write kept_manifest.csv and dropped_manifest.csv.

Usage:
  python3 filter_jtreg_seeds.py SRC_ROOT OUT_ROOT
    [--javac /path/to/javac] [--java /path/to/java]
    [--timeout-seconds N] [--include "globs"] [--exclude "globs"]
    [--flatten] [--dry-run] [--max-files N]
    [--classpath "/extra/dirs/or/jars{:|;}more"]  # uses os.pathsep

Notes:
  - Default include is "test/**".
  - This does NOT rewrite sources.
  - Exit code 0 = success; timeout / nonzero exit = drop.
"""

import argparse
import csv
import os
import shutil
import subprocess
import sys
from pathlib import Path
from typing import List, Optional

MAIN_TEXT_SNIPPET = "static void main("


def iter_java_with_main_text(src_root: Path,
                             include_globs: Optional[List[str]],
                             exclude_globs: Optional[List[str]]):
    if not include_globs:
        include_globs = ["test/**"]

    # Collect directories matching include globs
    dirs = []
    for pat in include_globs:
        for p in src_root.glob(pat):
            if p.is_dir():
                dirs.append(p)
    if not dirs:
        dirs = [src_root]

    for d in dirs:
        for java in d.rglob("*.java"):
            if java.name == "module-info.java":
                continue
            if exclude_globs:
                skip = False
                for ex in exclude_globs:
                    if java.match(ex):
                        skip = True
                        break
                if skip:
                    continue
            try:
                text = java.read_text(encoding="utf-8", errors="ignore")
            except Exception:
                continue
            if MAIN_TEXT_SNIPPET in text:
                yield java


def ensure_clean_dir(p: Path, dry_run: bool):
    if p.exists():
        if not dry_run:
            shutil.rmtree(p)
    if not dry_run:
        p.mkdir(parents=True, exist_ok=True)


def copy_preserving(src_file: Path, src_root: Path, out_root: Path,
                    flatten: bool, dry_run: bool) -> Path:
    if flatten:
        dest = out_root / src_file.name
        if not dry_run:
            dest.parent.mkdir(parents=True, exist_ok=True)
            base, suff, i = dest.stem, dest.suffix, 1
            while dest.exists():
                dest = dest.with_name(f"{base}_{i}{suff}")
                i += 1
            shutil.copy2(src_file, dest)
        return dest
    else:
        rel = src_file.relative_to(src_root)
        dest = out_root / rel
        if not dry_run:
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src_file, dest)
        return dest


def compile_sources(javac: str, sources: List[Path], classes_out: Path,
                    classpath: str, dry_run: bool) -> subprocess.CompletedProcess:
    cmd = [javac, "-d", str(classes_out)]
    if classpath:
        cmd += ["-cp", classpath]
    cmd += [str(s) for s in sources]
    print(f"[compile] {' '.join(cmd)}")
    if dry_run:
        return subprocess.CompletedProcess(cmd, 0, "", "")
    return subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)


def list_top_level_fqcns(classes_root: Path):
    for c in classes_root.rglob("*.class"):
        # Skip inner/anonymous classes
        if "$" in c.stem:
            continue
        rel = c.relative_to(classes_root)
        yield ".".join(rel.with_suffix("").parts)


def run_main(java: str, classpath: str, fqcn: str, timeout_s: int, dry_run: bool) -> subprocess.CompletedProcess:
    cmd = [java]
    if classpath:
        cmd += ["-cp", classpath]
    cmd += [fqcn]
    if dry_run:
        return subprocess.CompletedProcess(cmd, 0, "", "")
    return subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=timeout_s)


def sibling_sources_of(java_file: Path) -> List[Path]:
    # Compile all .java in the same directory (same package) – common jtreg pattern
    return sorted([p for p in java_file.parent.glob("*.java")])


def write_manifest(path: Path, rows, header=("path", "reason")):
    if not rows:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(header)
        for r in rows:
            w.writerow(r)


def compile_testlib(javac: str, src_root: Path, out_dir: Path, extra_cp: str, dry_run: bool) -> Optional[Path]:
    testlib_dir = src_root / "test" / "lib"
    if not testlib_dir.exists():
        return None
    ensure_clean_dir(out_dir, dry_run)
    sources = list(testlib_dir.rglob("*.java"))
    if not sources:
        return None
    res = compile_sources(javac, sources, out_dir, extra_cp, dry_run)
    if res.returncode != 0:
        sys.stderr.write("[warn] failed to compile test/lib; proceeding without it.\n")
        return None
    return out_dir


def main():
    ap = argparse.ArgumentParser(description="Copy, compile, and run jtreg tests; keep only those that run.")
    ap.add_argument("src_root", type=Path, help="Path to the cloned JDK repo root")
    ap.add_argument("out_root", type=Path, help="Destination directory for kept Java seeds")
    ap.add_argument("--flatten", action="store_true", help="Do not preserve directory structure in output")
    ap.add_argument("--include", default="", help='Comma-separated include globs under src_root (default: "test/**")')
    ap.add_argument("--exclude", default="", help="Comma-separated exclude globs")
    ap.add_argument("--timeout-seconds", type=int, default=10, help="Per-test runtime timeout (default: 10)")
    ap.add_argument("--javac", default="javac", help="Path to javac (default: on PATH)")
    ap.add_argument("--java", default="java", help="Path to java (default: on PATH)")
    ap.add_argument("--classpath", default="", help="Extra classpath entries (use OS path separator)")
    ap.add_argument("--dry-run", action="store_true", help="Simulate actions only; don't copy/delete files")
    ap.add_argument("--max-files", type=int, default=0, help="Process at most N files (0 = all)")
    args = ap.parse_args()

    src_root = args.src_root.resolve()
    out_root = args.out_root.resolve()
    work_root = out_root / ".work"
    classes_dir = work_root / "classes"
    testlib_classes = work_root / "testlib-classes"

    include_globs = [g.strip() for g in args.include.split(",") if g.strip()] or None
    exclude_globs = [g.strip() for g in args.exclude.split(",") if g.strip()] or None

    pathsep = os.pathsep

    kept, dropped = [], []

    # 1) compile test/lib once (with any user-provided extra CP)
    testlib_cp_dir = compile_testlib(args.javac, src_root, testlib_classes, args.classpath, args.dry_run)
    compiled_testlib_cp = str(testlib_cp_dir) if testlib_cp_dir else ""

    # 2) iterate candidates
    processed = 0
    for src in iter_java_with_main_text(src_root, include_globs, exclude_globs):
        dest = copy_preserving(src, src_root, out_root, args.flatten, args.dry_run)

        # Clean per-file classes dir
        ensure_clean_dir(classes_dir, args.dry_run)

        # Build compile CP = (user cp) + (compiled testlib)
        compile_cp = args.classpath
        if compiled_testlib_cp:
            compile_cp = compiled_testlib_cp if not compile_cp else f"{compile_cp}{pathsep}{compiled_testlib_cp}"

        # Compile candidate + siblings from ORIGINAL location (dest is just the copy we keep/drop)
        sources = sibling_sources_of(src)
        res = compile_sources(args.javac, sources, classes_dir, compile_cp, args.dry_run)

        if res.returncode != 0:
            # drop
            if not args.dry_run:
                try:
                    dest.unlink()
                except FileNotFoundError:
                    pass
            dropped.append((str(dest), "compile_failed"))
            print(f"[DROP] {dest} (compile_failed)")
        else:
            # Try to run each top-level class; success if any returns 0
            success = False
            last_reason = "nonzero_exit_or_timeout"
            candidates = list(list_top_level_fqcns(classes_dir))
            if not candidates:
                last_reason = "no_classes_emitted"
            else:
                run_cp = str(classes_dir)
                if compiled_testlib_cp:
                    run_cp = f"{compiled_testlib_cp}{pathsep}{run_cp}"
                if args.classpath:
                    run_cp = f"{args.classpath}{pathsep}{run_cp}" if not compiled_testlib_cp else f"{args.classpath}{pathsep}{run_cp}"
                for fqcn in candidates:
                    try:
                        rp = run_main(args.java, run_cp, fqcn, args.timeout_seconds, args.dry_run)
                        if rp.returncode == 0:
                            success = True
                            break
                    except subprocess.TimeoutExpired:
                        last_reason = "timeout"
                    except Exception:
                        last_reason = "run_exception"

            # Clean compiled classes
            if classes_dir.exists() and not args.dry_run:
                shutil.rmtree(classes_dir, ignore_errors=True)

            if success:
                kept.append((str(dest), "ok"))
                print(f"[KEEP] {dest} (ok)")
            else:
                if not args.dry_run:
                    try:
                        dest.unlink()
                    except FileNotFoundError:
                        pass
                dropped.append((str(dest), last_reason))
                print(f"[DROP] {dest} ({last_reason})")

        processed += 1
        if args.max_files and processed >= args.max_files:
            break

    # Clean testlib classes (we only keep sources)
    if testlib_classes.exists() and not args.dry_run:
        shutil.rmtree(testlib_classes, ignore_errors=True)

    write_manifest(out_root / "kept_manifest.csv", kept)
    write_manifest(out_root / "dropped_manifest.csv", dropped)

    print(f"\nDone. Kept {len(kept)} file(s), dropped {len(dropped)} file(s).")
    if kept:
        print(f"Kept list at: {out_root / 'kept_manifest.csv'}")
    if dropped:
        print(f"Dropped list at: {out_root / 'dropped_manifest.csv'}")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(130)
