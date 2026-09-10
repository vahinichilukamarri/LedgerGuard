package com.ledgerguard.accounts.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreateAccountRequest(

        @NotBlank(message = "name is required")
        String name,

        @NotBlank(message = "currency is required")
        @Pattern(regexp = "[A-Za-z]{3}", message = "currency must be a 3-letter ISO-4217 code")
        String currency) {
}
