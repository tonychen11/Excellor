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
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
class QuestionService {
    private static final Dotenv dotenv = Dotenv.load();
    private static final String GEMINI_API_KEY = dotenv.get("GEMINI_API_KEY");
    private static final String GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=";
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SlidingWindowRateLimiter dynamicRateLimiter = new SlidingWindowRateLimiter(15, Duration.ofMinutes(1).plusSeconds(5));
    public QuestionService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public void createQuestions(String inputFilePath, String outputFilePath) {
        List<Map<String, String>> generatedData = new ArrayList<>();
        int i = 0;

        try (Reader in = new FileReader(inputFilePath);
             CSVParser parser = new CSVParser(in, CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build())) {

            for (CSVRecord record : parser) {
                String subject = record.get("Subject");
                String subtopic = record.get("Subtopic");
                String description = record.get("Description");

                String prompt = String.format("", subject, subtopic, description);

                String response = callGeminiApi(prompt);
                parseResponseUSMLE(subject, subtopic, response, generatedData);
                System.out.println(i++);
                System.out.println(Instant.now());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        writeToCsvUSMLE(outputFilePath, generatedData);
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
            System.out.println(response);
        }
    }

    public void improveQuestionsMCAT(String inputFilePath, String outputFilePath) {
        List<Map<String, String>> records = readCsvMCAT(inputFilePath);
        writeToCsvMCAT(outputFilePath, records);
    }

    private List<Map<String, String>> readCsvMCAT(String filePath) {
        List<Map<String, String>> records = new ArrayList<>();
        int i = 0;

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

                String prompt = String.format("""
                    """,
                        rowData.get("Bank"),
                        rowData.get("Subject"),
                        rowData.get("Subtopic"),
                        rowData.get("Question"),
                        rowData.get("Option A"),
                        rowData.get("Option B"),
                        rowData.get("Option C"),
                        rowData.get("Option D"),
                        rowData.get("Answer"),
                        record.get("Explanation"),
                        record.get("Justification")
                );
                records.add(processRow(rowData, prompt));
                System.out.println(i++);
                System.out.println(Instant.now());
            }
        } catch (IOException e) {
            throw new RuntimeException("Error reading CSV file", e);
        }

        return records;
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

    public void improveQuestionsUSMLE(String inputFilePath, String outputFilePath) {
        List<Map<String, String>> records = readCsvUSMLE(inputFilePath);
        writeToCsvUSMLE(outputFilePath, records);
    }

    private List<Map<String, String>> readCsvUSMLE(String filePath) {
        List<Map<String, String>> records = new ArrayList<>();
        int i = 0;

        try (Reader in = new FileReader(filePath);
             CSVParser parser = new CSVParser(in, CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build())) {
            for (CSVRecord record : parser) {
                Map<String, String> rowData = Map.of(
                        "Subject", record.get("Subject"),
                        "Subtopic", record.get("Subtopic"),
                        "Question", record.get("Question"),
                        "Option A", record.get("Option A"),
                        "Option B", record.get("Option B"),
                        "Option C", record.get("Option C"),
                        "Option D", record.get("Option D"),
                        "Option E", record.get("Option E"),
                        "Answer", record.get("Answer")
                );

                String prompt = String.format("""

                    """,
                        rowData.get("Subject"),
                        rowData.get("Subtopic"),
                        rowData.get("Question"),
                        rowData.get("Option A"),
                        rowData.get("Option B"),
                        rowData.get("Option C"),
                        rowData.get("Option D"),
                        rowData.get("Option E"),
                        rowData.get("Answer"),
                        record.get("Explanation"),
                        record.get("Justification")
                );
                records.add(processRow(rowData, prompt));
                System.out.println(i++);
                System.out.println(Instant.now());
            }
        } catch (IOException e) {
            throw new RuntimeException("Error reading CSV file", e);
        }

        return records;
    }

    private void writeToCsvUSMLE(String filePath, List<Map<String, String>> data) {
        String userHome = System.getProperty("user.home");
        Path downloadPath = Paths.get(userHome, "Downloads", filePath);

        try (Writer out = new FileWriter(downloadPath.toFile());
             CSVPrinter printer = new CSVPrinter(out, CSVFormat.DEFAULT.builder()
                     .setHeader("Subject", "Subtopic", "Question", "Option A", "Option B", "Option C", "Option D", "Option E", "Answer", "Explanation", "Justification")
                     .build())) {

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

    private Map<String, String> processRow(Map<String, String> rowData, String prompt) {
        String response = callGeminiApi(prompt);
        return parseImprovedResponse(rowData, response);
    }

    public void generateQuestionsBasedOnQuestionUSMLE(String inputFilePath, String outputFilePath) {
        List<Map<String, String>> records = readCsvForGeneratingUSMLE(inputFilePath);
        writeToCsvUSMLE(outputFilePath, records);
    }

    private List<Map<String, String>> readCsvForGeneratingUSMLE(String filePath) {
        List<Map<String, String>> records = new ArrayList<>();
        int i = 0;

        try (Reader in = new FileReader(filePath);
             CSVParser parser = new CSVParser(in, CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build())) {
            for (CSVRecord record : parser) {
                String prompt = String.format("""
                                                                 
                    """,
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
                String subject = record.get("Subject");
                String subtopic = record.get("Subtopic");
                records.add(processRowGenerationUSMLE(subject, subtopic, prompt));
                System.out.println(i++);
                System.out.println(Instant.now());
            }
        } catch (IOException e) {
            throw new RuntimeException("Error reading CSV file", e);
        }

        return records;
    }

    private Map<String, String> processRowGenerationUSMLE(String subject, String subtopic, String prompt) {
        String response = callGeminiApi(prompt);
        return parseResponsesGenerationUSMLE(subject, subtopic, response);
    }

    public void generateQuestionsBasedOnQuestionMCAT(String inputFilePath, String outputFilePath) {
        List<Map<String, String>> records = readCsvForGeneratingMCAT(inputFilePath);
        writeToCsvMCAT(outputFilePath, records);
    }

    private List<Map<String, String>> readCsvForGeneratingMCAT(String filePath) {
        List<Map<String, String>> records = new ArrayList<>();
        int i = 0;

        try (Reader in = new FileReader(filePath);
             CSVParser parser = new CSVParser(in, CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build())) {
            for (CSVRecord record : parser) {
                String prompt = String.format("""
                                                                 
                    """,
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
                String bank = record.get("Bank");
                String subject = record.get("Subject");
                String subtopic = record.get("Subtopic");
                records.add(processRowGenerationMCAT(bank, subject, subtopic, prompt));
                System.out.println(i++);
                System.out.println(Instant.now());
            }
        } catch (IOException e) {
            throw new RuntimeException("Error reading CSV file", e);
        }

        return records;
    }


    private Map<String, String> processRowGenerationMCAT(String bank, String subject, String subtopic, String prompt) {
        String response = callGeminiApi(prompt);
        return parseResponsesGenerationMCAT(bank, subject, subtopic, response);
    }


    private String callGeminiApi(String prompt) {
        try {
            dynamicRateLimiter.waitForPermission();
            return sendRequest(prompt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Rate limiting interrupted", e);
        }
    }

    private String sendRequest(String prompt) {
        int retryCount = 0;
        int maxRetries = 5;  // Maximum number of retries
        long retryDelay = 2000;  // Initial retry delay (1 second)

        while (retryCount < maxRetries) {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);

                String requestBody = generateGeminiRequestBody(prompt);
                HttpEntity<String> request = new HttpEntity<>(requestBody, headers);

                ResponseEntity<String> response = restTemplate.exchange(
                        GEMINI_API_URL + GEMINI_API_KEY, HttpMethod.POST, request, String.class);

                return response.getBody();  // If the request is successful, return the response body

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


    private Map<String, String> parseImprovedResponse(Map<String, String> rowData, String response) {
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
            //throw new RuntimeException("Error parsing API response", e);
            System.out.println(response);
            return new HashMap<>();
        }
    }

    private Map<String, String> parseResponsesGenerationMCAT(String bank, String subject, String subtopic, String response) {
        try {
            JsonNode rootNode = objectMapper.readTree(response);
            String jsonResponse = rootNode.path("candidates")
                    .get(0).path("content").path("parts").get(0).path("text").asText();

            jsonResponse = cleanJson(jsonResponse);

            QuestionResponseNew questionResponse = objectMapper.readValue(jsonResponse, QuestionResponseNew.class);

            Map<String, String> row = new LinkedHashMap<>();

            row.put("Bank", bank);
            row.put("Subject", subject);
            row.put("Subtopic", subtopic);
            row.put("Question", questionResponse.getQuestion());
            row.put("Option A", questionResponse.getOptions().get("A"));
            row.put("Option B", questionResponse.getOptions().get("B"));
            row.put("Option C", questionResponse.getOptions().get("C"));
            row.put("Option D", questionResponse.getOptions().get("D"));
            row.put("Answer", questionResponse.getAnswer());
            row.put("Explanation", questionResponse.getExplanation());
            row.put("Justification", questionResponse.getJustification());

            return row;
        } catch (Exception e) {
            System.out.println(response);
            return new HashMap<>();
        }
    }

    private Map<String, String> parseResponsesGenerationUSMLE(String subject, String subtopic, String response) {
        try {
            JsonNode rootNode = objectMapper.readTree(response);
            String jsonResponse = rootNode.path("candidates")
                    .get(0).path("content").path("parts").get(0).path("text").asText();

            jsonResponse = cleanJson(jsonResponse);

            QuestionResponseNew questionResponse = objectMapper.readValue(jsonResponse, QuestionResponseNew.class);

            Map<String, String> row = new LinkedHashMap<>();

            row.put("Subject", subject);
            row.put("Subtopic", subtopic);
            row.put("Question", questionResponse.getQuestion());
            row.put("Option A", questionResponse.getOptions().get("A"));
            row.put("Option B", questionResponse.getOptions().get("B"));
            row.put("Option C", questionResponse.getOptions().get("C"));
            row.put("Option D", questionResponse.getOptions().get("D"));
            row.put("Option E", questionResponse.getOptions().get("D"));
            row.put("Answer", questionResponse.getAnswer());
            row.put("Explanation", questionResponse.getExplanation());
            row.put("Justification", questionResponse.getJustification());

            return row;
        } catch (Exception e) {
            System.out.println(response);
            return new HashMap<>();
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
}