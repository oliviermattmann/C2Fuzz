import re

def extract_macros(file_path):
    macros = []
    with open(file_path, "r") as f:
        for line in f:
            match = re.match(r'\s*macro\((\w+)\)', line)
            if match:
                macros.append(match.group(1))
    return macros

def generate_java_class(macros, class_name="FeatureMap"):
    java_code = []
    java_code.append("import java.util.*;")
    java_code.append("")
    java_code.append(f"public class {class_name} {{")
    java_code.append("    public static Map<String, Integer> featureMap;")
    java_code.append("")
    java_code.append("    static {")
    java_code.append("        featureMap = new LinkedHashMap<>();")
    for macro in macros:
        java_code.append(f'        featureMap.put("{macro}", 0);')
    java_code.append("    }")
    java_code.append("}")
    return "\n".join(java_code)

if __name__ == "__main__":
    file_path = "/home/oli/Documents/education/eth/msc-thesis/code/jdk/src/hotspot/share/opto/classes.hpp"  # replace with your actual filename
    macros = extract_macros(file_path)
    java_class_code = generate_java_class(macros, "FeatureMap")
    with open("FeatureMap.java", "w") as f:
        f.write(java_class_code)
    print("✅ FeatureMap.java has been generated.")
