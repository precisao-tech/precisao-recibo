package com.precisao.recibo.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientException;

import java.util.Map;

@Service
public class CpfValidacaoService {

    private final RestTemplate restTemplate = new RestTemplate();
    
    @Value("${api.cpf.validacao.url:https://api.receitaws.com.br/v1/cpf}")
    private String apiUrl;

    /**
     * Valida se um CPF existe na base do governo
     * @param cpf CPF sem formatação (apenas números)
     * @return true se o CPF é válido e existe, false caso contrário
     */
    public boolean validarCpfNoGoverno(String cpf) {
        if (cpf == null || cpf.isBlank()) {
            return false;
        }

        // Remove formatação
        String cpfLimpo = cpf.replaceAll("[^0-9]", "");
        
        // Verifica formato básico
        if (cpfLimpo.length() != 11) {
            return false;
        }

        // Verifica se todos os dígitos são iguais
        if (cpfLimpo.matches("(\\d)\\1{10}")) {
            return false;
        }

        // Valida dígitos verificadores primeiro
        if (!validarDigitosVerificadores(cpfLimpo)) {
            return false;
        }

        try {
            // Consulta API externa para verificar se CPF existe
            String url = apiUrl + "/" + cpfLimpo;
            
            @SuppressWarnings("unchecked")
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
            
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = (Map<String, Object>) response.getBody();
                
                // Verifica se a resposta indica CPF válido
                // A API ReceitaWS retorna "status": "OK" para CPFs válidos
                String status = (String) body.get("status");
                
                return "OK".equalsIgnoreCase(status) || 
                       (status == null && !body.containsKey("erro"));
            }
            
            return false;
        } catch (RestClientException e) {
            System.err.println("Erro ao consultar API de validação de CPF (pode ser rate limit): " + e.getMessage());
            return validarDigitosVerificadores(cpfLimpo);
        } catch (Exception e) {
            System.err.println("Erro inesperado ao validar CPF: " + e.getMessage());
            return validarDigitosVerificadores(cpfLimpo);
        }
    }

    /**
     * Valida os dígitos verificadores do CPF
     */
    private boolean validarDigitosVerificadores(String cpf) {
        if (cpf.length() != 11) {
            return false;
        }

        // Calcula o primeiro dígito verificador
        int soma = 0;
        for (int i = 0; i < 9; i++) {
            soma += Character.getNumericValue(cpf.charAt(i)) * (10 - i);
        }
        int resto = (soma * 10) % 11;
        if (resto == 10 || resto == 11) resto = 0;
        if (resto != Character.getNumericValue(cpf.charAt(9))) {
            return false;
        }

        soma = 0;
        for (int i = 0; i < 10; i++) {
            soma += Character.getNumericValue(cpf.charAt(i)) * (11 - i);
        }
        resto = (soma * 10) % 11;
        if (resto == 10 || resto == 11) resto = 0;
        return resto == Character.getNumericValue(cpf.charAt(10));
    }
}

