package org.example;

import lombok.Data;

import java.util.Map;

@Data
public class CodingExplanation {
    private String[] explanation;
    private String code;
    private String timeComplexity;
    private String spaceComplexity;
}
