package fuzzer.util;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;


public class GraphParser {

    public List<GraphResult> parseGraphs(String inputFile) {
        List<GraphResult> results = new ArrayList<>();
        // There are usually multiple graphs per test case (multiple per method even)
        int graphIndex = 0;

        try {
            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            Document doc = dBuilder.parse(new File(inputFile));
            doc.getDocumentElement().normalize();

            NodeList groupList = doc.getElementsByTagName("group");

            for (int g = 0; g < groupList.getLength(); g++) {
                Element group = (Element) groupList.item(g);

                // Extract method name
                String methodName = "unknown_method";
                NodeList props = group.getElementsByTagName("p");
                for (int i = 0; i < props.getLength(); i++) {
                    Element p = (Element) props.item(i);
                    if ("name".equals(p.getAttribute("name"))) {
                        methodName = p.getTextContent().trim()
                                .replace(" ", "_")
                                .replace("/", "_");
                        break;
                    }
                }

                NodeList graphList = group.getElementsByTagName("graph");
                for (int gi = 0; gi < graphList.getLength(); gi++) {
                    Element graph = (Element) graphList.item(gi);
                    if ("Before Matching".equals(graph.getAttribute("name"))) {
                        graphIndex++;

                        Map<String, Integer> counts = new HashMap<>();
                        NodeList allNodes = graph.getElementsByTagName("node");
                        for (int n = 0; n < allNodes.getLength(); n++) {
                            Element node = (Element) allNodes.item(n);

                            String nodeType = null;
                            NodeList pList = node.getElementsByTagName("p");
                            for (int pi = 0; pi < pList.getLength(); pi++) {
                                Element p = (Element) pList.item(pi);
                                if ("name".equals(p.getAttribute("name"))) {
                                    nodeType = p.getTextContent().trim();
                                    break;
                                }
                            }
                            if (nodeType == null || nodeType.isEmpty()) {
                                nodeType = node.getAttribute("id");
                            }

                            if (nodeType != null && !nodeType.isEmpty()) {
                                counts.put(nodeType, counts.getOrDefault(nodeType, 0) + 1);
                            }
                        }

                        GraphResult result = new GraphResult(methodName, graphIndex, counts);
                        results.add(result);
                    }
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return results;
    }

}
