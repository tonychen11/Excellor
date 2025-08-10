package org.example;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cdimascio.dotenv.Dotenv;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class CodingPractice {
    private static final Dotenv dotenv = Dotenv.load();
    private static final String OPENAI_API_KEY = dotenv.get("OPENAI_API_KEY");
    private static final String OPENAI_API_URL = "https://api.openai.com/v1/chat/completions";
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SlidingWindowRateLimiter dynamicRateLimiter = new SlidingWindowRateLimiter(15, Duration.ofMinutes(1).plusSeconds(5));

    public CodingPractice(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
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

    public void generateCodingQuestionExplanations(String inputFilePath, String outputFilePath) {
        List<Map<String, String>> records = readCsv(inputFilePath);
        writeToCsv(outputFilePath, records);
    }

    private List<Map<String, String>> readCsv(String filePath) {
        List<Map<String, String>> records = new ArrayList<>();
        int i = 0;

        try (Reader in = new FileReader(filePath);
            CSVParser parser = new CSVParser(in, CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build())) {
            for (CSVRecord record : parser) {
                String prompt = String.format(""" 
                                Your role: A coding assistant specializing in high-speed, efficient algorithmic problem-solving. Your responses must be:
                                • Maximally concise while ensuring clarity.
                                • Easily scannable under time pressure (short bullet points).
                                • Devoid of filler words like "a," "the," and unnecessary phrases.
                                • Readable in seconds and immediately actionable.
                                                                
                                Response Should Include:
                                • Algorithm Pattern(s): Clearly state techniques used (e.g., DP, Two Pointers).
                                • Key Steps:
                                	• Concise, essential logic (avoid fluff).
                                	• Ensure all necessary implementation details are covered.
                                	• Max 5 bullet points
                                • Code Implementation (Java).
                                • Time & Space Complexity: Big-O notation with a short justification.
                                					
                                Example JSON Output:
                                {
                                "explanation": [
                                "Pattern: [Algorithm Pattern(s)]",
                                "[Essential Step 1]",
                                "[Essential Step 2]",
                                "[Essential Step 3]",
                                "[Additional steps if necessary]"
                                ],
                                "code": "[Java implementation of the solution]",
                                "timeComplexity": "[Big-O notation with justification]",
                                "spaceComplexity": "[Big-O notation with justification]"
                                }
                                Problem Statement: Leetcode - %s 
                                """,
                        record.get("Question").trim()
                );
                records.add(processRow(record.get("Question").trim(), prompt));
                System.out.println(i++);
                System.out.println(Instant.now());
            }
        } catch (IOException e) {
            throw new RuntimeException("Error reading CSV file", e);
        }

        return records;
    }

    private Map<String, String> processRow(String question, String prompt) {
        String response = callChatGptApi(prompt);
        return parseResponseChatGPT(question, response);
    }

    private String callChatGptApi(String prompt) {
        try {
            dynamicRateLimiter.waitForPermission();
            return sendRequestChatGPT(prompt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Rate limiting interrupted", e);
        }
    }

    private String sendRequestChatGPT(String prompt) {
        int retryCount = 0;
        int maxRetries = 5;
        long retryDelay = 2000;

        while (retryCount < maxRetries) {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.set("Authorization", "Bearer " + OPENAI_API_KEY);

                String requestBody = generateChatGptRequestBody(prompt);

                HttpEntity<String> request = new HttpEntity<>(requestBody, headers);

                ResponseEntity<String> response = restTemplate.exchange(
                        OPENAI_API_URL, HttpMethod.POST, request, String.class);

                return response.getBody();

            } catch (HttpClientErrorException.TooManyRequests e) {
                retryCount++;

                if (retryCount < maxRetries) {
                    // Exponential backoff: increasing delay between retries
                    try {
                        System.out.println("Rate limit exceeded. Retrying in " + retryDelay + "ms...");
                        TimeUnit.MILLISECONDS.sleep(retryDelay);
                        retryDelay *= 2;  // Double the delay on each retry
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();  // Restore interrupted status
                        throw new RuntimeException("Thread interrupted during retry", ie);
                    }
                } else {
                    // Max retries exceeded, throw an exception
                    //throw new RuntimeException("Rate limit exceeded after " + maxRetries + " retries", e);
                    return "Retry didn't work";
                }
            }
        }
        return null;  // This line will never be reached due to the maxRetries logic, but it's here for safety
    }

    private String generateChatGptRequestBody(String prompt) {
        String systemContent =
                """
                
                """;

        // Create a map for the messages
        List<Map<String, Object>> messages = new ArrayList<>();

        // Add system message to list
        Map<String, Object> systemMessage = new HashMap<>();
        systemMessage.put("role", "system");
        systemMessage.put("content", systemContent);
        messages.add(systemMessage);

        // Add user message to list
        Map<String, Object> userMessage = new HashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", prompt);
        messages.add(userMessage);

        // Create final map for the full JSON structure
        Map<String, Object> jsonMap = new HashMap<>();
        jsonMap.put("model", "gpt-4o");
        jsonMap.put("messages", messages);
        jsonMap.put("temperature", 0.2);

        ObjectMapper objectMapper = new ObjectMapper();
        try {
            return objectMapper.writeValueAsString(jsonMap);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private Map<String, String> parseResponseChatGPT(String question, String response) {
        try {
            JsonNode rootNode = objectMapper.readTree(response);
            String jsonResponse = rootNode.path("choices")
                    .get(0).path("message").path("content").asText();

            jsonResponse = cleanJson(jsonResponse);

            CodingExplanation output = objectMapper.readValue(jsonResponse, CodingExplanation.class);

            Map<String, String> row = new LinkedHashMap<>();
            row.put("Question", question);
            row.put("Explanation", Arrays.stream(output.getExplanation())
                    .collect(Collectors.joining("\n")));

            return row;
        } catch (Exception e) {
            System.out.println(response);
            return new HashMap<>();
        }
    }

    private Map<String, String> parseResponseGemini(String question, String response) {
        try {
            JsonNode rootNode = objectMapper.readTree(response);
            String jsonResponse = rootNode.path("candidates")
                    .get(0).path("content").path("parts").get(0).path("text").asText();

            jsonResponse = cleanJson(jsonResponse);

            CodingExplanation output = objectMapper.readValue(jsonResponse, CodingExplanation.class);

            Map<String, String> row = new LinkedHashMap<>();
            row.put("Question", question);
            row.put("Explanation", Arrays.stream(output.getExplanation())
                    .collect(Collectors.joining(" ")));

            return row;
        } catch (Exception e) {
            System.out.println(response);
            return new HashMap<>();
        }
    }
    private void writeToCsv(String filePath, List<Map<String, String>> data) {
        String userHome = System.getProperty("user.home");
        Path downloadPath = Paths.get(userHome, "Downloads", filePath);

        try (Writer out = new FileWriter(downloadPath.toFile());
             CSVPrinter printer = new CSVPrinter(out, CSVFormat.DEFAULT.builder()
                     .setHeader("Question", "Explanation")
                     .build())) {

            for (Map<String, String> record : data) {
                printer.printRecord(
                        record.get("Question"),
                        record.get("Explanation")
                );
            }
        } catch (IOException e) {
            throw new RuntimeException("Error writing CSV file", e);
        }
    }
}
