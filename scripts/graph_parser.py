import xml.etree.ElementTree as ET
import os
import pandas as pd
from collections import Counter
import math
import sys

# The user-provided file path
input_file = "graph13.xml"

# The output directory for the extracted graphs and CSV
output_dir = "before_matching_graphs"
os.makedirs(output_dir, exist_ok=True)

try:
    # Safely parse the XML file
    tree = ET.parse(input_file)
    root = tree.getroot()
except ET.ParseError as e:
    print(f"Error parsing XML file: {e}", file=sys.stderr)
    sys.exit(1)
except FileNotFoundError as e:
    print(f"Error: {e}", file=sys.stderr)
    sys.exit(1)

records = []  # List to store a dictionary for each graph's data
graph_index = 0

# Find all 'group' elements in the XML root
for group in root.findall("group"):
    # Correctly find the method name, which is nested in a <p> tag
    method_name_element = group.find("properties/p[@name='name']")
    if method_name_element is not None and method_name_element.text:
        method_name = method_name_element.text.strip().replace(" ", "_").replace("/", "_")
    else:
        # Fallback if method name is not found
        method_name = group.get("name", "unknown_method").replace(" ", "_").replace("/", "_")
    
    # Iterate through all 'graph' elements within the current 'group'
    for graph in group.findall("graph"):
        # We are only interested in graphs with the name "Before Matching"
        if graph.get("name") == "Before Matching":
            graph_index += 1
            
            # Save the 'Before Matching' graph to a separate XML file
            out_path = os.path.join(output_dir, f"{method_name}_before_matching_{graph_index}.xml")
            ET.ElementTree(graph).write(out_path, encoding="utf-8", xml_declaration=True)
            
            # Count node types based on their 'name' or 'id'
            node_counts = Counter()
            
            # Use XPath ('.//node') to find all node elements regardless of their nesting level
            # This is more robust than looking for specific parent tags.
            all_nodes = graph.findall(".//node")
            
            for node in all_nodes:
                # Prioritize the 'name' attribute which is in a nested <p> tag
                node_type_element = node.find("properties/p[@name='name']")
                if node_type_element is not None and node_type_element.text:
                    node_type = node_type_element.text.strip()
                else:
                    # Fallback to 'id' if 'name' is not available
                    node_type = node.get("id")
                
                if node_type:
                    node_counts[node_type] += 1
            
            # Calculate diversity metrics
            total_nodes = sum(node_counts.values())
            distinct_nodes = len(node_counts)
            
            entropy = 0.0
            # Avoid division by zero and log(0) errors
            if total_nodes > 0:
                for count in node_counts.values():
                    p = count / total_nodes
                    if p > 0:
                        entropy -= p * math.log2(p)
            
            # Create a dictionary to store the record and add it to the list
            record = {
                "method": method_name,
                "graph_id": graph_index,
                "total_nodes": total_nodes,
                "distinct_node_types": distinct_nodes,
                "entropy": entropy
            }
            record.update(node_counts)
            records.append(record)

# Convert the list of records to a pandas DataFrame for easier analysis
df = pd.DataFrame(records).fillna(0)

# Save the DataFrame to a CSV file for fuzzer feedback
csv_out = os.path.join(output_dir, "before_matching_node_counts.csv")
df.to_csv(csv_out, index=False)

print(f"Extracted {graph_index} 'Before Matching' graphs.")
print(f"Node histograms + diversity scores saved to {csv_out}.")
