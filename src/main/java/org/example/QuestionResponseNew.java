package org.example;

import lombok.Data;
import java.util.Map;

@Data
public class QuestionResponseNew {
    private String question;
    private Map<String, String> options;
    private String answer;
    private String explanation;
    private String justification;
}
