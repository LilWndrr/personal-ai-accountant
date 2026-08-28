package com.example.springaitest.service;

import com.example.springaitest.domain.Recipe;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

import java.util.List;

@RequiredArgsConstructor
@Slf4j
@Service
public class OllamaService {

    private final OllamaChatModel chatModel;
    private final ChatClient chatClient;

    public String getResponse(String message){
        log.info("Received message: {}" , message);

        //String response = chatModel.call(message);

        String response = chatClient.prompt().user(u->u.text("Response from gemma: {message}")
                .param("message",message)).call().content();

        log.info("Response from gemma: {}", response);

        return response;
    }

    public List<Recipe> getRecipe(String countryName){

        log.info("Received country name: {}", countryName);

       List<Recipe>  recipes = chatClient.prompt()
                .user(u->u.text("Get top 3 dishes recipes from this country {countryName}")
                        .param("countryName",countryName)).call().entity(new ParameterizedTypeReference<List<Recipe>>() {
                });

        assert recipes != null;
        int i=1;
        for(Recipe recipe : recipes){
            log.info(i+". Recipe : {}", recipe);
        }
        return recipes;
    }
}
