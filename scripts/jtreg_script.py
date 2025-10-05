# #!/usr/bin/env python3
# import argparse
# import os
# import shutil
# import subprocess
# import sys
# import tempfile
# from pathlib import Path

# SKIP_DIRS = {"target", "build", "out", ".git", ".idea", ".gradle"}

# def is_executable(p: Path) -> bool:
#     return p.is_file() and os.access(str(p), os.X_OK)

# def copy_tree(src: Path, dst: Path) -> Path:
#     if dst.exists():
#         raise FileExistsError(f"Destination already exists: {dst}")
#     shutil.copytree(src, dst)
#     return dst

# def find_java_files(root: Path):
#     for p in sorted(root.rglob("*.java")):
#         if any(part in SKIP_DIRS for part in p.parts):
#             continue
#         yield p

# def run_javac(javac: Path, file_path: Path, cwd: Path):
#     try:
#         res = subprocess.run(
#             [str(javac), str(file_path)],
#             cwd=str(cwd),
#             stdout=subprocess.PIPE,
#             stderr=subprocess.PIPE,
#             text=True
#         )
#         return res.returncode, res.stdout, res.stderr
#     except FileNotFoundError as e:
#         return 127, "", str(e)

# def delete_generated_classes(java_file: Path):
#     # Delete classes produced for this source: Base.class, Base$Inner.class, etc.
#     stem = java_file.stem
#     for cls in java_file.parent.glob(f"{stem}*.class"):
#         try:
#             cls.unlink()
#         except Exception:
#             pass

# def delete_if_empty_dirs(path: Path, stop_at: Path):
#     # Walk up deleting empty dirs until reaching stop_at (non-inclusive)
#     cur = path
#     try:
#         while cur != stop_at and cur != cur.parent:
#             if any(cur.iterdir()):
#                 break
#             cur.rmdir()
#             cur = cur.parent
#     except Exception:
#         pass

# def main():
#     parser = argparse.ArgumentParser(
#         description="Copy a Java source tree and compile each .java file independently; delete files that don't compile; record those that do."
#     )
#     parser.add_argument("--javac", required=True, type=Path, help="Path to javac executable")
#     parser.add_argument("--java", required=True, type=Path, help="Path to java executable (validated only)")
#     parser.add_argument("--src", required=True, type=Path, help="Path to the source directory to copy from")
#     parser.add_argument("--dst", type=Path, default=None, help="Destination for the working copy (optional)")
#     parser.add_argument("--tmp", action="store_true",
#                         help="Place the copy in a temp directory (ignored if --dst is provided)")
#     parser.add_argument("--ok-list", type=Path, required=True,
#                         help="Path to write successful test file paths (line-separated)")
#     parser.add_argument("--relative-to", type=Path, default=None,
#                         help="Base path to make entries in ok-list relative to (defaults to --src)")
#     parser.add_argument("--verbose", "-v", action="count", default=0, help="Increase output verbosity")
#     args = parser.parse_args()

#     javac = args.javac.resolve()
#     java = args.java.resolve()
#     src = args.src.resolve()
#     rel_base = (args.relative_to.resolve() if args.relative_to else src)

#     # Validations
#     if not src.is_dir():
#         print(f"[ERROR] Source directory does not exist: {src}", file=sys.stderr)
#         sys.exit(2)
#     if not is_executable(javac):
#         print(f"[ERROR] javac not found or not executable: {javac}", file=sys.stderr)
#         sys.exit(2)
#     if not is_executable(java):
#         print(f"[ERROR] java not found or not executable: {java}", file=sys.stderr)
#         sys.exit(2)

#     # Decide destination
#     if args.dst:
#         dst = args.dst.resolve()
#     else:
#         base = Path(tempfile.mkdtemp(prefix="java-copy-")) if args.tmp else src.parent
#         dst = (base if args.tmp else base / f"{src.name}-copy")
#         if not args.tmp and dst.exists():
#             i = 1
#             while (base / f"{src.name}-copy-{i}").exists():
#                 i += 1
#             dst = base / f"{src.name}-copy-{i}"

#     # Copy
#     try:
#         copy_tree(src, dst)
#     except FileExistsError as e:
#         print(f"[ERROR] {e}", file=sys.stderr)
#         sys.exit(3)

#     print(f"[INFO] Working copy: {dst}")

#     java_files = list(find_java_files(dst))
#     if not java_files:
#         print("[INFO] No .java files found.")
#         # Still create/empty ok-list
#         args.ok_list.write_text("")
#         sys.exit(0)

#     ok_list_entries = []
#     total = len(java_files)
#     ok_count = 0
#     fail_count = 0

#     for f in java_files:
#         rel_in_dst = f.relative_to(dst)
#         if args.verbose:
#             print(f"[INFO] Compiling: {rel_in_dst}")
#         code, out, err = run_javac(javac, f, cwd=dst)

#         if code == 0:
#             # Record successful test path (relative to rel_base)
#             # Map the path back to the equivalent under src so the relative math is stable
#             original_path = src / rel_in_dst
#             try:
#                 entry_path = original_path.relative_to(rel_base)
#             except ValueError:
#                 # If rel_base is not a prefix, fall back to absolute
#                 entry_path = original_path
#             ok_list_entries.append(str(entry_path))
#             ok_count += 1

#             # Clean emitted classes
#             delete_generated_classes(f)

#             if args.verbose > 1:
#                 so = out.strip()
#                 if so:
#                     print(so)
#         else:
#             # Delete the failed source file from the working copy
#             try:
#                 f.unlink()
#                 delete_if_empty_dirs(f.parent, dst)
#             except Exception:
#                 pass

#             fail_count += 1
#             print(f"[FAIL] {rel_in_dst}")
#             # Always show error for failed files
#             msg = (err if err.strip() else out).strip()
#             if msg:
#                 print(msg, file=sys.stderr)

#     # Write ok list (line separated)
#     args.ok_list.parent.mkdir(parents=True, exist_ok=True)
#     args.ok_list.write_text("\n".join(ok_list_entries) + ("\n" if ok_list_entries else ""))

#     # Summary
#     print("\n=== Summary ===")
#     print(f"Total files scanned: {total}")
#     print(f"Compiled OK:        {ok_count}")
#     print(f"Failed & deleted:   {fail_count}")
#     print(f"\n[INFO] Wrote successful paths to: {args.ok_list}")
#     print("[INFO] The working copy remains at:", dst)

# if __name__ == "__main__":
#     main()
#!/usr/bin/env python3
import argparse
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

SKIP_DIRS = {"target", "build", "out", ".git", ".idea", ".gradle"}

def is_executable(p: Path) -> bool:
    return p.is_file() and os.access(str(p), os.X_OK)

def copy_tree(src: Path, dst: Path) -> Path:
    if dst.exists():
        raise FileExistsError(f"Destination already exists: {dst}")
    shutil.copytree(src, dst)
    return dst

def find_java_files(root: Path):
    for p in sorted(root.rglob("*.java")):
        if any(part in SKIP_DIRS for part in p.parts):
            continue
        yield p

def run_javac(javac: Path, file_path: Path, cwd: Path, classpath: str | None = None):
    cmd = [str(javac)]
    if classpath:
        cmd += ["-cp", classpath]
    cmd.append(str(file_path))
    try:
        res = subprocess.run(
            cmd,
            cwd=str(cwd),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True
        )
        return res.returncode, res.stdout, res.stderr
    except FileNotFoundError as e:
        return 127, "", str(e)

def delete_generated_classes(java_file: Path):
    stem = java_file.stem
    for cls in java_file.parent.glob(f"{stem}*.class"):
        try:
            cls.unlink()
        except Exception:
            pass

def delete_if_empty_dirs(path: Path, stop_at: Path):
    cur = path
    try:
        while cur != stop_at and cur != cur.parent:
            if any(cur.iterdir()):
                break
            cur.rmdir()
            cur = cur.parent
    except Exception:
        pass

def shorten_err(msg: str, max_lines: int = 5) -> str:
    lines = [ln for ln in (msg or "").splitlines() if ln.strip()]
    return "\n".join(lines[:max_lines])

def discover_testlib(wb_jar: Path) -> Path | None:
    lib_dir = wb_jar.parent
    # Exact match preferred
    candidate = lib_dir / "test-lib.jar"
    if candidate.is_file():
        return candidate
    # Fallback: any *testlib*.jar (pick one deterministically)
    matches = sorted(lib_dir.glob("*test-lib*.jar"))
    return matches[0] if matches else None

def main():
    parser = argparse.ArgumentParser(
        description="Compile each Java file independently, write results incrementally, log failures, and detect tests needing wb/testlib."
    )
    parser.add_argument("--javac", required=True, type=Path, help="Path to javac")
    parser.add_argument("--java", required=True, type=Path, help="Path to java (validated only)")
    parser.add_argument("--src", required=True, type=Path, help="Source directory to copy")
    parser.add_argument("--dst", type=Path, default=None, help="Destination working copy (optional)")
    parser.add_argument("--tmp", action="store_true", help="Put working copy in a unique temp dir (ignored if --dst)")
    parser.add_argument("--ok-list", type=Path, required=True, help="Append-only list of tests that compile without libs")
    parser.add_argument("--needs-libs-list", type=Path, required=True, help="Append-only list of tests that compile only with libs")
    parser.add_argument("--log-file", type=Path, required=True, help="Append-only log of failures and reasons")
    parser.add_argument("--wb-jar", type=Path, required=True, help="Path to wb.jar (used to locate testlib.jar)")
    parser.add_argument("--relative-to", type=Path, default=None, help="Base for path entries (default: --src)")
    parser.add_argument("--verbose", "-v", action="count", default=0, help="Increase output verbosity")
    args = parser.parse_args()

    javac = args.javac.resolve()
    java = args.java.resolve()
    src = args.src.resolve()
    wb_jar = args.wb_jar.resolve()
    rel_base = (args.relative_to.resolve() if args.relative_to else src)

    # Validations
    if not src.is_dir():
        print(f"[ERROR] Source directory does not exist: {src}", file=sys.stderr)
        sys.exit(2)
    for exe, name in [(javac, "javac"), (java, "java")]:
        if not is_executable(exe):
            print(f"[ERROR] {name} not found or not executable: {exe}", file=sys.stderr)
            sys.exit(2)
    if not wb_jar.is_file():
        print(f"[ERROR] wb.jar not found: {wb_jar}", file=sys.stderr)
        sys.exit(2)

    testlib_jar = discover_testlib(wb_jar)
    if testlib_jar is None:
        print(f"[WARN] Could not locate testlib.jar next to wb.jar in {wb_jar.parent}.", file=sys.stderr)

    # Prepare destination
    if args.dst:
        dst = args.dst.resolve()
    else:
        base = Path(tempfile.mkdtemp(prefix="java-copy-")) if args.tmp else src.parent
        dst = (base if args.tmp else base / f"{src.name}-copy")
        if not args.tmp and dst.exists():
            i = 1
            while (base / f"{src.name}-copy-{i}").exists():
                i += 1
            dst = base / f"{src.name}-copy-{i}"

    # Copy tree
    try:
        copy_tree(src, dst)
    except FileExistsError as e:
        print(f"[ERROR] {e}", file=sys.stderr)
        sys.exit(3)

    print(f"[INFO] Working copy: {dst}")

    # Ensure parent dirs exist for output files
    for p in [args.ok_list, args.needs_libs_list, args.log_file]:
        p.parent.mkdir(parents=True, exist_ok=True)

    # Open files in append+line-buffered style
    ok_fp = args.ok_list.open("a", encoding="utf-8", errors="replace")
    need_fp = args.needs_libs_list.open("a", encoding="utf-8", errors="replace")
    log_fp = args.log_file.open("a", encoding="utf-8", errors="replace")

    # Show tool versions (optional)
    if args.verbose:
        try:
            v1 = subprocess.run([str(javac), "-version"], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            v2 = subprocess.run([str(java), "-version"], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            print(f"[INFO] {v1.stdout.strip()}")
            print(f"[INFO] {v2.stdout.strip()}")
        except Exception as e:
            print(f"[WARN] Could not query tool versions: {e}", file=sys.stderr)

    java_files = list(find_java_files(dst))
    total = len(java_files)
    ok_plain = 0
    ok_with_libs = 0
    failed = 0

    # Build classpath string for libs if available
    libs_cp = None
    if testlib_jar and wb_jar:
        # On Windows you'd use ';'—here we focus on Unix (your path shows Linux)
        libs_cp = f"{testlib_jar}:{wb_jar}"

    try:
        for f in java_files:
            rel_in_dst = f.relative_to(dst)
            original_path = src / rel_in_dst
            try:
                entry_path = original_path.relative_to(rel_base)
            except ValueError:
                entry_path = original_path

            if args.verbose:
                print(f"[INFO] Compiling: {rel_in_dst}")

            # 1) Try plain compile (no classpath)
            code, out, err = run_javac(javac, f, cwd=dst, classpath=None)

            if code == 0:
                # success as-is
                delete_generated_classes(f)
                ok_fp.write(str(entry_path) + "\n")
                ok_fp.flush()
                ok_plain += 1
                if args.verbose > 1:
                    so = out.strip()
                    if so:
                        print(so)
                continue

            # 2) If that failed, try with wb/testlib if we have them
            tried_libs = False
            if libs_cp:
                tried_libs = True
                code2, out2, err2 = run_javac(javac, f, cwd=dst, classpath=libs_cp)
                if code2 == 0:
                    delete_generated_classes(f)
                    need_fp.write(str(entry_path) + "\n")
                    need_fp.flush()
                    ok_with_libs += 1
                    if args.verbose:
                        print(f"[INFO] (needs libs) {rel_in_dst}")
                    continue
                else:
                    # Still failed with libs
                    reason = shorten_err(err or out)
                    reason2 = shorten_err(err2 or out2)
                    log_fp.write(f"[FAIL] {entry_path}\n")
                    if tried_libs:
                        log_fp.write("Reason (no cp):\n" + (reason or "(empty)") + "\n")
                        log_fp.write("Reason (with libs):\n" + (reason2 or "(empty)") + "\n\n")
                    else:
                        log_fp.write((reason or "(empty)") + "\n\n")
                    log_fp.flush()
            else:
                # Failed and no libs to try
                reason = shorten_err(err or out)
                log_fp.write(f"[FAIL] {entry_path}\n{reason or '(empty)'}\n\n")
                log_fp.flush()

            # Delete failed source from working copy, tidy empty dirs
            try:
                f.unlink()
                delete_if_empty_dirs(f.parent, dst)
            except Exception:
                pass
            failed += 1

        # Summary
        print("\n=== Summary ===")
        print(f"Total files scanned: {total}")
        print(f"Compiled OK (plain): {ok_plain}")
        print(f"Compiled OK (needs libs): {ok_with_libs}")
        print(f"Failed & deleted: {failed}")
        print(f"\n[INFO] ok-list:        {args.ok_list}")
        print(f"[INFO] needs-libs-list:{args.needs_libs_list}")
        print(f"[INFO] failure log:    {args.log_file}")
        print(f"[INFO] Working copy:   {dst}")

    finally:
        ok_fp.close()
        need_fp.close()
        log_fp.close()

if __name__ == "__main__":
    main()
