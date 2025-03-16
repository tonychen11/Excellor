package org.example;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cdimascio.dotenv.Dotenv;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Service
class QuestionService {
    private static final Dotenv dotenv = Dotenv.load();
    private static final String GEMINI_API_KEY = dotenv.get("GEMINI_API_KEY");
    private static final String GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=";
    private static final String OPENAI_API_KEY = dotenv.get("OPENAI_API_KEY");
    private static final String OPENAI_API_URL = "https://api.openai.com/v1/chat/completions";
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public QuestionService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public void processQuestions(String inputFilePath, String outputFilePath) {
        List<Map<String, String>> generatedData = new ArrayList<>();

        try (Reader in = new FileReader(inputFilePath);
             CSVParser parser = new CSVParser(in, CSVFormat.DEFAULT.withFirstRecordAsHeader())) {

            for (CSVRecord record : parser) {
                String subject = record.get("Subject");
                String subtopic = record.get("Subtopic");
                String description = record.get("Description");

                String prompt = String.format("", subject, subtopic, description);

                String response = callGeminiApi(prompt);
                parseResponseUSMLE(subject, subtopic, response, generatedData);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        writeToCsvUSMLE(outputFilePath, generatedData);
    }

    public void improveQuestionsMCAT(String inputFilePath, String outputFilePath) {
        List<Map<String, String>> records = readCsvMCAT(inputFilePath);
        writeToCsvMCAT(outputFilePath, records);
    }

    private List<Map<String, String>> readCsvMCAT(String filePath) {
        List<Map<String, String>> records = new ArrayList<>();
        int i = 1;

        try (Reader in = new FileReader(filePath);
             CSVParser parser = new CSVParser(in, CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build())) {
            for (CSVRecord record : parser) {
                Map<String, String> rowData = Map.of(
                        "Bank", record.get("Bank"),
                        "Subject", record.get("Subject"),
                        "Subtopic", record.get("Subtopic"),
                        "Question", record.get("Question"),
                        "Option A", record.get("Option A"),
                        "Option B", record.get("Option B"),
                        "Option C", record.get("Option C"),
                        "Option D", record.get("Option D"),
                        "Answer", record.get("Answer")
                );

                String prompt = String.format("");
                records.add(processRow(rowData, prompt));
                System.out.println(i++);
            }
        } catch (IOException e) {
            throw new RuntimeException("Error reading CSV file", e);
        }

        return records;
    }

    private Map<String, String> processRow(Map<String, String> rowData, String prompt) {
        String response = callGeminiApi(prompt);
        return parseResponseMCAT(rowData, response);
    }

    private String callGeminiApi(String prompt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        String requestBody = generateGeminiRequestBody(prompt);
        HttpEntity<String> request = new HttpEntity<>(requestBody, headers);

        ResponseEntity<String> response = restTemplate.exchange(
                GEMINI_API_URL + GEMINI_API_KEY, HttpMethod.POST, request, String.class);

        return response.getBody();
    }

    private String callChatGptApi(String prompt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + OPENAI_API_KEY);

        String requestBody = generateChatGptRequestBody(prompt);

        HttpEntity<String> request = new HttpEntity<>(requestBody, headers);

        ResponseEntity<String> response = restTemplate.exchange(
                OPENAI_API_URL, HttpMethod.POST, request, String.class);

        return response.getBody();
    }

    public static String generateGeminiRequestBody(String prompt) {
        try {
            Map<String, Object> text = Map.of("text", prompt);
            Map<String, Object> parts = Map.of("parts", List.of(text));
            Map<String, Object> contents = Map.of("contents", List.of(parts));

            ObjectMapper objectMapper = new ObjectMapper();
            return objectMapper.writeValueAsString(contents);

        } catch (Exception e) {
            throw new RuntimeException("Error generating JSON request body", e);
        }
    }

    private String generateChatGptRequestBody(String prompt) {
        return """
        {
            "model": "gpt-4",
            "messages": [
                {"role": "user", "content": "%s"}
            ]
        }
        """.formatted(prompt);
    }

    public void parseResponseUSMLE(String subject, String subtopic, String response, List<Map<String, String>> generatedData) {
        try {
            JsonNode rootNode = objectMapper.readTree(response);

            String jsonResponse = rootNode.path("candidates")
                    .get(0).path("content").path("parts").get(0).path("text").asText();

            jsonResponse = cleanJson(jsonResponse);

            List<QuestionResponse> questionResponses = objectMapper.readValue(jsonResponse, new TypeReference<>() {});

            for(QuestionResponse questionResponse : questionResponses){
                Map<String, String> parsedData = new LinkedHashMap<>();
                parsedData.put("Subject", subject);
                parsedData.put("Subtopic", subtopic);
                parsedData.put("Question", questionResponse.getQuestion());
                parsedData.put("Option A", questionResponse.getOptions().get("A"));
                parsedData.put("Option B", questionResponse.getOptions().get("B"));
                parsedData.put("Option C", questionResponse.getOptions().get("C"));
                parsedData.put("Option D", questionResponse.getOptions().get("D"));
                parsedData.put("Option E", questionResponse.getOptions().get("E"));
                parsedData.put("Answer", questionResponse.getAnswer());
                parsedData.put("Explanation", questionResponse.getExplanation());

                StringBuilder justificationBuilder = new StringBuilder();
                questionResponse.getJustification().forEach((key, value) ->
                        justificationBuilder.append(key).append(": ").append(value).append(" ")
                );

                parsedData.put("Justification", justificationBuilder.toString().trim());
                generatedData.add(parsedData);
            }
        } catch (Exception e) {
            throw new RuntimeException("Error parsing response", e);
        }
    }

    private Map<String, String> parseResponseMCAT(Map<String, String> rowData, String response) {
        try {
            JsonNode rootNode = objectMapper.readTree(response);
            String jsonResponse = rootNode.path("candidates")
                    .get(0).path("content").path("parts").get(0).path("text").asText();

            jsonResponse = cleanJson(jsonResponse);

            ImprovedResponse questionResponse = objectMapper.readValue(jsonResponse, ImprovedResponse.class);

            Map<String, String> row = new LinkedHashMap<>(rowData);

            row.put("Explanation", questionResponse.getExplanation());
            row.put("Justification", questionResponse.getJustification());

            return row;
        } catch (Exception e) {
            throw new RuntimeException("Error parsing API response", e);
        }
    }

    public static String cleanJson(String jsonResponse) {
        if (jsonResponse.startsWith("```json")) {
            jsonResponse = jsonResponse.substring(7);
        }
        if (jsonResponse.endsWith("```")) {
            jsonResponse = jsonResponse.substring(0, jsonResponse.length() - 3);
        }

        return jsonResponse;
    }

    private void writeToCsvUSMLE(String filePath, List<Map<String, String>> data) {
        String userHome = System.getProperty("user.home");
        Path downloadPath = Paths.get(userHome, "Downloads", filePath);

        try (Writer out = new FileWriter(downloadPath.toFile());
             CSVPrinter printer = new CSVPrinter(out, CSVFormat.DEFAULT.withHeader("Subject", "Subtopic", "Question", "Option A", "Option B", "Option C", "Option D", "Option E", "Answer", "Explanation", "Justification"))) {

            for (Map<String, String> record : data) {
                printer.printRecord(
                        record.get("Subject"),
                        record.get("Subtopic"),
                        record.get("Question"),
                        record.get("Option A"),
                        record.get("Option B"),
                        record.get("Option C"),
                        record.get("Option D"),
                        record.get("Option E"),
                        record.get("Answer"),
                        record.get("Explanation"),
                        record.get("Justification")
                );
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void writeToCsvMCAT(String filePath, List<Map<String, String>> data) {
        String userHome = System.getProperty("user.home");
        Path downloadPath = Paths.get(userHome, "Downloads", filePath);

        try (Writer out = new FileWriter(downloadPath.toFile());
             CSVPrinter printer = new CSVPrinter(out, CSVFormat.DEFAULT.builder()
                     .setHeader("Bank", "Subject", "Subtopic", "Question", "Option A", "Option B", "Option C", "Option D", "Answer", "Explanation", "Justification")
                     .build())) {

            for (Map<String, String> record : data) {
                printer.printRecord(
                        record.get("Bank"),
                        record.get("Subject"),
                        record.get("Subtopic"),
                        record.get("Question"),
                        record.get("Option A"),
                        record.get("Option B"),
                        record.get("Option C"),
                        record.get("Option D"),
                        record.get("Answer"),
                        record.get("Explanation"),
                        record.get("Justification")
                );
            }
        } catch (IOException e) {
            throw new RuntimeException("Error writing CSV file", e);
        }
    }
}