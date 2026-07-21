package com.cosmic.vpplugin.mock;

/**
 * Risposta LLM "finta", usata finche' l'endpoint HTTP reale non e' disponibile.
 * Riprende l'esempio "Insert a New Customer" del paper CosMet (Fig. 1 / Tab. 1)
 * e lo estende con un secondo Use Case in relazione di <<include>> e uno in
 * <<extend>>, cosi' da poter testare subito frecce e ereditarieta' nel
 * diagramma generato.
 *
 * Nota didattica (mismatch UC vs FP): "Inserisci nuovo cliente" e' UN solo
 * Use Case ma viene scomposto in DUE Processi Funzionali COSMIC
 * (validazione dati + salvataggio), a dimostrazione del punto sollevato dal
 * professore.
 */
public final class MockLlmResponse {

    private MockLlmResponse() { }

    public static final String JSON =
        "{\n" +
        "  \"projectName\": \"Hotel Management System\",\n" +
        "  \"actors\": [\n" +
        "    { \"id\": \"actor_manager\", \"name\": \"Hotel Manager\", \"description\": \"Gestisce le anagrafiche clienti\" },\n" +
        "    { \"id\": \"actor_system\",  \"name\": \"Sistema Validazione\", \"description\": \"Sotto-sistema di validazione dati\" }\n" +
        "  ],\n" +
        "  \"useCases\": [\n" +
        "    {\n" +
        "      \"id\": \"uc_insert_customer\",\n" +
        "      \"name\": \"Insert a New Customer\",\n" +
        "      \"primaryActorId\": \"actor_manager\",\n" +
        "      \"specification\": \"Permette all'utente di inserire un nuovo cliente nel sistema.\",\n" +
        "      \"mainScenario\": [\n" +
        "        \"The user clicks the 'New customer' button\",\n" +
        "        \"The system shows a form with editable fields\",\n" +
        "        \"The user fills in the fields and submits the form\",\n" +
        "        \"The system validates and records the new customer\",\n" +
        "        \"The system shows a confirmation message\"\n" +
        "      ],\n" +
        "      \"exceptions\": [\n" +
        "        \"The system shows an error message stating that the provided data are not valid\"\n" +
        "      ],\n" +
        "      \"relations\": {\n" +
        "        \"includes\": [\"uc_validate_data\"],\n" +
        "        \"extends\": []\n" +
        "      },\n" +
        "      \"functionalProcesses\": [\n" +
        "        {\n" +
        "          \"fpId\": \"fp_1\",\n" +
        "          \"fpName\": \"Insert new customer - Validation\",\n" +
        "          \"triggeringEvent\": \"User clicks 'New customer' button\",\n" +
        "          \"subProcesses\": [\n" +
        "            { \"step\": 1, \"description\": \"Enter customer data\", \"functionalUser\": \"Hotel Manager\", \"dataMovementType\": null, \"dataGroup\": null, \"objectOfInterest\": null }\n" +
        "          ]\n" +
        "        },\n" +
        "        {\n" +
        "          \"fpId\": \"fp_2\",\n" +
        "          \"fpName\": \"Insert new customer - Storage\",\n" +
        "          \"triggeringEvent\": \"Validated data ready to be stored\",\n" +
        "          \"subProcesses\": [\n" +
        "            { \"step\": 1, \"description\": \"Validate and store customer\", \"functionalUser\": \"Hotel Manager\", \"dataMovementType\": null, \"dataGroup\": null, \"objectOfInterest\": null },\n" +
        "            { \"step\": 2, \"description\": \"Show confirmation / error message\", \"functionalUser\": \"Hotel Manager\", \"dataMovementType\": null, \"dataGroup\": null, \"objectOfInterest\": null }\n" +
        "          ]\n" +
        "        }\n" +
        "      ]\n" +
        "    },\n" +
        "    {\n" +
        "      \"id\": \"uc_validate_data\",\n" +
        "      \"name\": \"Validate Customer Data\",\n" +
        "      \"primaryActorId\": \"actor_system\",\n" +
        "      \"specification\": \"Use case incluso: verifica formalmente i dati del cliente (codice fiscale, email, data di nascita).\",\n" +
        "      \"mainScenario\": [ \"The system checks surname, name, fiscal code, date of birth and email\" ],\n" +
        "      \"exceptions\": [],\n" +
        "      \"relations\": { \"includes\": [], \"extends\": [] },\n" +
        "      \"functionalProcesses\": []\n" +
        "    },\n" +
        "    {\n" +
        "      \"id\": \"uc_show_error\",\n" +
        "      \"name\": \"Show Validation Error\",\n" +
        "      \"primaryActorId\": \"actor_manager\",\n" +
        "      \"specification\": \"Use case di estensione: mostrato solo se la validazione fallisce.\",\n" +
        "      \"mainScenario\": [ \"The system shows an error message stating that the provided data are not valid\" ],\n" +
        "      \"exceptions\": [],\n" +
        "      \"relations\": {\n" +
        "        \"includes\": [],\n" +
        "        \"extends\": [ { \"targetId\": \"uc_insert_customer\", \"extensionPoint\": \"Dati non validi (5.a1)\" } ]\n" +
        "      },\n" +
        "      \"functionalProcesses\": []\n" +
        "    }\n" +
        "  ]\n" +
        "}\n";
}
