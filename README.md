# Excellor

**Excellor** is an AI-powered web application designed to generate high-quality **USMLE Step 1** practice questions. By taking in a CSV file with medical topics, Excellor leverages advanced AI to create well-structured multiple-choice questions, providing users with an Excel file containing the generated questions and explanations.

## 🚀 Features
- **AI-Generated USMLE Questions** – Converts medical concepts into well-crafted multiple-choice questions.
- **CSV Input & Excel Output** – Upload a CSV file and receive an Excel file with structured questions.
- **High-Quality Explanations** – Each question includes detailed explanations and justifications.
- **Customizable Topics** – Supports various medical subjects to tailor exam preparation.

## 📂 How It Works
1. **Upload a CSV file** containing columns like *Subject*, *Subtopic*, and *Description*.
2. **Excellor processes the input** and generates multiple-choice questions.
3. **Download the output Excel file**, which includes:
   - The generated question
   - Answer choices (A, B, C, D, E)
   - Correct answer
   - Explanation & justification for incorrect choices

## 🛠️ Technologies Used
- **Spring Boot** – Backend framework for handling requests and processing data.
- **Google Gemini API** – AI-powered question generation.
- **Apache POI** – Excel file generation.
- **Lombok** – Reduces boilerplate code.
- **Maven** – Dependency management.