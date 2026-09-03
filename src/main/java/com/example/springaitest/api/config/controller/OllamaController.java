package com.example.springaitest.api.config.controller;

import com.example.springaitest.api.config.domain.Recipe;
import com.example.springaitest.api.config.service.OllamaService;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@AllArgsConstructor
@RequestMapping("/api/v1/ollama")
public class OllamaController {

    private final OllamaService ollamaService;

    @PostMapping(consumes = "application/json", produces = "application/json", path="/prompt")
    public String getOllamaResponse(@RequestBody String input){
        return ollamaService.getResponse(input);
    }
    @GetMapping("/country")
    public List<Recipe> getRecipeByCountry(@RequestParam String countryName){
        return ollamaService.getRecipe(countryName);
    }

}

