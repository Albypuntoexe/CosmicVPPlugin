package com.cosmic.vpplugin.service;

/**
 * Prompt del metodo CosMet (De Vito et al.) - FILE GENERATO AUTOMATICAMENTE da {@code prompt.py}
 * del professore tramite uno script che estrae le costanti con {@code ast.literal_eval}.
 * I testi qui sotto sono quindi IDENTICI carattere per carattere all'originale Python
 * (i caratteri non-ASCII, es. le virgolette tipografiche, sono scritti come escape unicode).
 * NON modificare a mano {@link #SYSTEM_SPLIT_UC} e {@link #SYSTEM_COSMIC_MEASURE}: se il professore
 * aggiorna prompt.py, rigenerare questo file.
 *
 * Mapping con functions.py:
 *   split_sentences()  ->  SYSTEM_SPLIT_UC  +  {@link #userSplit(String)}                (Step 1)
 *   cosmic_analyze()   ->  SYSTEM_COSMIC_MEASURE (+ addendum JSON) + {@link #userMeasure} (Step 2)
 */
public final class CosmicPrompts {

    private CosmicPrompts() { }

    // ---------------------------------------------------------------- STEP 1 (Sentence Splitter)

    public static final String SYSTEM_SPLIT_UC =
            "split all the following sentences, numbering the new sentences so that each new sentence is grammatically correct.\n" +
            "\n" +
            "Example 1:\n" +
            "\n" +
            "MAIN SCENARIO:\n" +
            "- The system validates the given date interval: the start date must be greater than or equal to today's date, and the end date must be greater than or equal to the start date. Moreover, the system verifies that the room type is not null and the number of adults is greater than 0. If the number of children is greater than 0, it checks if their ages have been specified.\n" +
            "- The system searches for the season configuration in the given interval. The system stores the reservation header containing the ID, date interval, reservation date, status, number of adults, number of children, fiscal code, total amount, and \"special requests.\" The status of the new reservation is \"Reserved.\".  In addition, the system stores the booked room(s) for the given date interval, then saves the requested services for the selected room(s) and the children's ages.\n" +
            "- The system searches for and lists the data.\n" +
            "\n" +
            "Split:\n" +
            "MAIN SCENARIO:\n" +
            "1. The system validates the given date interval.\n" +
            "2. The start date must be greater than or equal to today's date.\n" +
            "3. The end date must be greater than or equal to the start date.\n" +
            "4. The system verifies that the room type is not null.\n" +
            "5. The number of adults must be greater than 0.\n" +
            "6. If the number of children is greater than 0, it checks if their ages have been specified.\n" +
            "7. The system searches for the season configuration in the given interval.\n" +
            "8. The system stores the reservation header containing various information.\n" +
            "9. The status of the new reservation is \"Reserved\".\n" +
            "10. The system stores the booked room(s) for the given date interval.\n" +
            "11. The system saves the requested services for the selected room(s).\n" +
            "12. The system saves the children's ages.\n" +
            "13. The system searches for the data.\n" +
            "14. The system lists the data.\n" +
            "\n" +
            "###\n" +
            "\n" +
            "Example 2:\n" +
            "\n" +
            "MAIN SCENARIO:\n" +
            "- C-Reg validates the data to ensure the proper formats and checks whether a Professor of that ID\n" +
            "already exists. If the entered data are valid, C-Reg creates a record for the new Professor.\n" +
            "- The system checks that: the surname and name fields contain at least two characters, the fiscal code field is valid, the date of birth is correct, the email address is valid and records the new customer in persistent storage.\n" +
            "\n" +
            "Alternative flow no. 1:\n" +
            "2.a1. if data entered is not valid, C-Reg displays one or more error messages, for example:\n" +
            "'Professor ID unknown', Professor name already exists', \"Professor Data Invalid\". The Registrar\n" +
            "can then change or correct the data.\n" +
            "2.a2. The flow of events resumes at step 2 of the MAIN SCENARIO.\n" +
            "\n" +
            "Alternative flow no. 2:\n" +
            "2.b1. if data entered is not valid, C-Reg displays one or more error messages, for example:\n" +
            "'Professor ID unknown', Professor name already exists', \"Professor Data Invalid\". The Registrar\n" +
            "can cancel the operation.\n" +
            "2.b2. The flow of events ends.\n" +
            "\n" +
            "Exceptions:\n" +
            "- The system shows an error message stating that the provided data are not valid. The flow of events ends.\n" +
            "\n" +
            "Split:\n" +
            "MAIN SCENARIO:\n" +
            "1. C-Reg validates the data to ensure proper formats.\n" +
            "2. C-Reg checks if a Professor with the same ID already exists.\n" +
            "3. If the entered data is valid, C-Reg creates a record for the new Professor.\n" +
            "4. The system checks that the surname and name fields contain at least two characters.\n" +
            "5. The system checks that the fiscal code field is valid.\n" +
            "6. The system checks that the date of birth is correct.\n" +
            "7. The system checks that the email address is valid.\n" +
            "8. The system records the new customer in persistent storage.\n" +
            "\n" +
            "Alternative flow no. 1:\n" +
            "2.a1. if data entered is not valid, C-Reg displays one or more error messages, for example:\n" +
            "'Professor ID unknown', Professor name already exists', \"Professor Data Invalid\".\n" +
            "2.a2. The Registrar can then change or correct the data.\n" +
            "2.a3. The flow of events resumes at step 2 of the MAIN SCENARIO.\n" +
            "\n" +
            "Alternative flow no. 2:\n" +
            "2.b1. if data entered is not valid, C-Reg displays one or more error messages, for example:\n" +
            "'Professor ID unknown', Professor name already exists', \"Professor Data Invalid\".\n" +
            "2.b2. The Registrar can cancel the operation. \n" +
            "2.b3. The flow of events ends.\n" +
            "\n" +
            "Exceptions:\n" +
            "9. The system shows an error message stating that the provided data are not valid. \n" +
            "10. The flow of events ends.\n" +
            "\n" +
            "###\n" +
            "\n" +
            "Example 3:\n" +
            "\n" +
            "MAIN SCENARIO:\n" +
            "1. The system requests the Sensor to change its status, sending the 'turn on/off' signal.\n" +
            "2. The system requests the Sensor to remove the data, sending the 'Remove data' command.\n" +
            "3.  The system updates the Reservation details and the Boked room , setting the reservation status to 'accepted' and the room attributes as follows: guests = 5, reserved = 'Yes'.\n" +
            "4. The system inserts the reservation and the booked room details or updates the guest file, increasing the number of guests.\n" +
            "5. The system requests the Sensor to process the 'turn on/off' signal.\n" +
            "6. The system displays the ship details (height, length, price).\n" +
            "7. The system displays a page with the ship details: height, length, price.\n" +
            "8. The user fills in the description and note fields.\n" +
            "\n" +
            "Split:\n" +
            "MAIN SCENARIO:\n" +
            "1. The system requests the Sensor to change its status, sending the 'turn on/off' signal.\n" +
            "2. The system requests the Sensor to remove the data, sending the 'Remove data' command.\n" +
            "3. The system updates the Reservation details, setting the reservation status to 'accepted'.\n" +
            "4. The system updates the Boked room, setting the room attributes as follows: guests = 5, reserved = 'Yes'.\n" +
            "5. The system inserts the reservation details.\n" +
            "6. The system inserts the booked room details.\n" +
            "7. The system updates the guest file, increasing the number of guests.\n" +
            "8. The system requests the Sensor to process the 'turn on/off' signal.\n" +
            "9. The system displays the ship details (height, length, price).\n" +
            "10. The system displays a page with the ship details: height, length, price.\n" +
            "11. The user fills in the description and note fields.\n" +
            "\n" +
            "###\n" +
            "\n" +
            "Example 4:\n" +
            "\n" +
            "MAIN SCENARIO:\n" +
            "1. The user clicks the button \"Search\".\n" +
            "2. The system searches the patient details.\n" +
            "3. The system shows a page with the patient details: name, surname, date of birth, and fiscal code.\n" +
            "\n" +
            "Split:\n" +
            "MAIN SCENARIO:\n" +
            "1. The user clicks the button \"Search\".\n" +
            "2. The system searches the patient details.\n" +
            "3. The system shows a page with the patient details: name, surname, date of birth, and fiscal code.\n" +
            "\n" +
            "###\n" +
            "\n" +
            "Example 5:\n" +
            "\n" +
            "MAIN SCENARIO:\n" +
            "1. The user fills in the name, surname, and fiscal code fields and optionally the notes field, and submits the form.\n" +
            "2. The system saves the the patient data and shows a confirmation message.\n" +
            "\n" +
            "Split:\n" +
            "MAIN SCENARIO:\n" +
            "1. The user fills in the name, surname, and fiscal code fields and optionally the notes field.\n" +
            "2. The user submits the form.\n" +
            "3. The system saves the the patient data.\n" +
            "4. The system shows a confirmation message.\n" +
            "\n" +
            "###\n" +
            "\n" +
            "Example 6:\n" +
            "\n" +
            "MAIN SCENARIO:\n" +
            "1. The user clicks the \"New patient\" button\n" +
            "2. The system shows a form with the following editable fields: name, surname, address and notes.\n" +
            "3. The user fills in the name, surname, and address fields. He (or She) optionally fills in the notes field and submits the form.\n" +
            "4. The system saves the the patient data and shows a confirmation message.\n" +
            "\n" +
            "Split:\n" +
            "MAIN SCENARIO:\n" +
            "1. The user clicks the \"New patient\" button\n" +
            "2. The system shows a form with the following editable fields: name, surname, address and notes.\n" +
            "3. The user fills in the name, surname, and address fields. He (or She) optionally fills in the notes field.\n" +
            "4. The user submits the form.\n" +
            "5. The system saves the the patient data.\n" +
            "6. The system shows a confirmation message.\n" +
            "\n" +
            "###\n" +
            "\n" +
            "Example 7:\n" +
            "\n" +
            "MAIN SCENARIO\n" +
            "1\tDoctor\tThe doctor clicks the \"New patient\" button\n" +
            "2\tSystem\tThe system shows a form with the following editable fields: code, name, notes and surname.\n" +
            "3\tDoctor\tThe doctor fills in the code, name, and surname fields. He (or She) optionally fills in the notes field and submits the form.\n" +
            "4\tSystem\tThe system checks that the patient code contains at least one character, the patient surname contains at least three characters, and the patient name contains at least five characters and records the new patient details.\n" +
            "5\tSystem\tThe system shows a confirmation message.\n" +
            "\n" +
            "Split:\n" +
            "MAIN SCENARIO\n" +
            "1\tDoctor\tThe doctor clicks the \"New patient\" button\n" +
            "2\tSystem\tThe system shows a form with the following editable fields: code, name, notes and surname.\n" +
            "3\tDoctor\tThe doctor fills in the code, name, and surname fields. He (or She) optionally fills in the notes field.\n" +
            "4\tDoctor\tThe doctor submits the form.\n" +
            "5\tSystem\tThe system checks that the patient code contains at least one character, the patient surname contains at least three characters, and the patient name contains at least five characters.\n" +
            "6\tSystem\tThe system records the new patient details.\n" +
            "7\tSystem\tThe system shows a confirmation message.\n" +
            "\n" +
            "###\n" +
            "\n" +
            "Example 8:\n" +
            "\n" +
            "MAIN SCENARIO\n" +
            "1\tCaregiver, Doctor\tThe user clicks the \"View patient details\" button relating to the patient of his (or her) interest\n" +
            "2\tSystem\tThe system searches for the patient with the given fiscal code\n" +
            "3\tSystem\tThe system displays the patient details: surname, name, date of birth, fiscal code, address, city, email address, notes, currently occupied room, and last occupied room.\n" +
            "\n" +
            "Split:\n" +
            "MAIN SCENARIO\n" +
            "1\tCaregiver, Doctor\tThe user clicks the \"View patient details\" button relating to the patient of his (or her) interest\n" +
            "2\tSystem\tThe system searches for the patient with the given fiscal code\n" +
            "3\tSystem\tThe system displays the patient details: surname, name, date of birth, fiscal code, address, city, email address, notes, currently occupied room, and last occupied room.\n" +
            "\n" +
            "###\n" +
            "\n" +
            "Example 9:\n" +
            "\n" +
            "MAIN SCENARIO\n" +
            "1\tThe user clicks the \"New patient\" button\n" +
            "2\tThe system shows a form with the following editable fields: surname, name, date of birth, fiscal code, address, city, and notes.\n" +
            "3\tThe user fills in the surname, name, date of birth, address, city, email address, fiscal code fields, and optionally the notes field and submits the form.\n" +
            "4\tThe system checks that: the surname and name fields contain at least two characters, the fiscal code field is valid, the date of birth is correct, the email address is valid and records the new patient in persistent storage.\n" +
            "5\tThe system shows a confirmation message.\n" +
            "\n" +
            "Split:\n" +
            "MAIN SCENARIO\n" +
            "1\tThe user clicks the \"New patient\" button\n" +
            "2\tThe system shows a form with the following editable fields: surname, name, date of birth, fiscal code, address, city, and notes.\n" +
            "3\tThe user fills in the surname, name, date of birth, address, city, email address, fiscal code fields, and optionally the notes field.\n" +
            "4\tThe user submits the form.\n" +
            "5\tThe system checks that: the surname and name fields contain at least two characters, the fiscal code field is valid, the date of birth is correct, the email address is valid.\n" +
            "6\tThe system records the new patient in persistent storage.\n" +
            "7\tThe system shows a confirmation message.\n" +
            "\n" +
            "###\n" +
            "\n" +
            "Example 10:\n" +
            "\n" +
            "MAIN SCENARIO\n" +
            "1\tSystem\tThe system lists the available rooms.\n" +
            "2\tCaregiver\tThe actor presses \"Reserve rooms\" relating to the rooms of his interest.\n" +
            "3\tSystem\tThe system reads from persistent storage the room details and searches for the requestable room services for the selected rooms.\n" +
            "4\tSystem\tThe system lists the selected rooms and the requestable room services for each room.\n" +
            "\n" +
            "Split:\n" +
            "MAIN SCENARIO\n" +
            "1\tSystem\tThe system lists the available rooms.\n" +
            "2\tCaregiver\tThe actor presses \"Reserve rooms\" relating to the rooms of his interest.\n" +
            "3\tSystem\tThe system reads from persistent storage the room details.\n" +
            "4\tSystem\tThe system  searches for the requestable room services for the selected rooms.\n" +
            "5\tSystem\tThe system lists the selected rooms. \n" +
            "6\tSystem\tThe system lists the requestable room services for each room.\n" +
            "\n" +
            "###\n" +
            "\n" +
            "Example 11:\n" +
            "\n" +
            "MAIN SCENARIO\n" +
            "1\tOperator\tThe user chooses the services and the cabins of his interest.\n" +
            "2\tOperator\tThe user sends his choices to the system and presses the Confirm button.\n" +
            "3\tSystem\tThe system stores the newly requested services for the reservation, removing the previous cabin services.\n" +
            "\n" +
            "Split:\n" +
            "MAIN SCENARIO\n" +
            "1\tOperator\tThe user chooses the services of his interest.\n" +
            "2\tOperator\tThe user chooses the cabins of his interest.\n" +
            "3\tOperator\tThe user sends his choices to the system and presses the Confirm button.\n" +
            "4\tSystem\tThe system stores the newly requested services for the reservation.\n" +
            "5\tSystem\tThe system removes the previous cabin services.\n" +
            "\n" +
            "###\n" +
            "\n" +
            "Example 12:\n" +
            "\n" +
            "MAIN SCENARIO\n" +
            "1\tShipowner\tThe shipowner fills in the search fields and presses the \"Search\" button.\n" +
            "2\tSystem\tThe system searches for ships and the associated categories that meet the search criteria.\n" +
            "3\tSystem\tThe system lists the categories and, for each category, lists the associated ship.\n" +
            "4\tShipowner\tThe shipowner selects the ship and the quantity as requested by the customer.\n" +
            "\n" +
            "Split:\n" +
            "MAIN SCENARIO\n" +
            "1\tShipowner\tThe shipowner fills in the search fields and presses the \"Search\" button.\n" +
            "2\tSystem\tThe system searches for ships.\n" +
            "3\tSystem\tThe system searches for the associated categories that meet the search criteria.\n" +
            "4\tSystem\tThe system lists the categories.\n" +
            "5\tSystem\tThe system lists the associated ship.\n" +
            "6\tShipowner\tThe shipowner selects the ship and the quantity as requested by the customer.\n" +
            "\n" +
            "###\n" +
            "\n" +
            "Example 13:\n" +
            "\n" +
            "6\tShipowner\tThe shipowner selects the ship and the quantity as requested by the customer.\n" +
            "\n" +
            "Split:\n" +
            "6\tShipowner\tThe shipowner selects the ship and the quantity as requested by the customer.\n" +
            "\n" +
            "###\n" +
            "\n" +
            "Example 14:\n" +
            "\n" +
            "3\tShipowner\tThe shipowner updates the description and (or) the note fields and presses the \"Save\" button.\n" +
            "\n" +
            "Split:\n" +
            "3\tShipowner\tThe shipowner updates the description and (or) the note fields.\n" +
            "3\tShipowner\tThe shipowner presses the \"Save\" button.\n" +
            "\n" +
            "###\n";

    /** USER_SPLIT.format(uc=lines) di prompt.py: "{uc}\n\nSplit:". */
    public static String userSplit(String uc) {
        return uc + "\n\nSplit:";
    }

    // ---------------------------------------------------------------- STEP 2 (COSMIC Analyzer)

    public static final String SYSTEM_COSMIC_MEASURE =
            "FP \u201cEnquire on a Professor\u2019s details\u201d: \n" +
            "1. When a Registrar wishes to enquire on the details of a Professor, he must first select the sub-option \u2018Enquire on a Professor\u2019. \n" +
            "2. Then, he must enter the Professor ID. \n" +
            "3. C-Reg searches for a Professor with the specified ID.\n" +
            "4. C-Reg displays a page (a form) with the Professor\u2019s name and address, and other details. \n" +
            "4. If a Professor with the specified ID is not found, C-Reg displays an error message, \"Professor Not Found.\" \n" +
            "5. The Registrar can then type in a different ID or cancel the operation.\n" +
            "6. If the Professor ID provided is not valid, C-Reg displays an error message, \"Invalid Professor ID.\"\n" +
            "\n" +
            "FP \u201cEnquire on a Professor\u2019s details\u201d measurement:\n" +
            "Triggering Event: The Registrar selects the sub-option \u2018Enquire on a Professor\u2019\n" +
            "Functional User: Registrar\n" +
            "Sub-Processes:\n" +
            "1. When a Registrar wishes to enquire on the details of a Professor, he must first select the sub-option \u2018Enquire on a Professor\u2019. \n" +
            "    No data movement.\n" +
            "2. Then, he must enter the Professor ID. \n" +
            "    FU (Registrar) \u2013 DG (Professor ID) \u2013 OOI (Professor) \u2013 Entry\n" +
            "3. C-Reg searches for a Professor with the specified ID. \n" +
            "    DG (Professor ID) \u2013 OOI (Professor) \u2013 Read.\n" +
            "4. C-Reg displays a page (a form) with the Professor\u2019s name and address, and other details.\n" +
            "    DG (Professor details) \u2013 OOI (Professor) \u2013 Exit.\n" +
            "5. If a Professor with the specified ID is not found, C-Reg displays an error message, \"Professor Not Found.\" \n" +
            "    DG (Messages) \u2013 OOI (Message) \u2013 Exit. It must be counted only once.\n" +
            "6. The Registrar can then type in a different ID or cancel the operation.\n" +
            "    No data movement.\n" +
            "7. If the Professor ID provided is not valid, C-Reg displays an error message, \"Invalid Professor ID.\"\n" +
            "    DG (Messages) \u2013 OOI (Message) \u2013 Exit. It must be counted only once.\n" +
            "\n" +
            "###\n" +
            "\n" +
            "FP \u201cAdd a Professor\u2019s details\u201d:\n" +
            "MAIN SCENARIO:\n" +
            "1. When a Registrar wishes to enter data about a new Professor, he selects the sub-option 'Add Professor'.\n" +
            "2. C-Reg displays a blank formatted screen for entry of Professor data.\n" +
            "3. The Registrar enters the following details for the Professor: ID, name and address, social security number, Department, qualifications and \n" +
            "contact details.\n" +
            "4. The Registrar presses \u2018Save\u2019.\n" +
            "5. C-Reg validates the data to ensure the proper formats.\n" +
            "6. C-Reg checks if the social security number is greater than 0.\n" +
            "7. C-Reg checks whether a Professor of that ID already exists.\n" +
            "8. C-Reg creates a record for the new Professor.\n" +
            "9. C-Reg displays a confirmation message.\n" +
            "\n" +
            "Alternative scenario no. 1:\n" +
            "1. If data entered is not valid, C-Reg displays one or more error messages, for example: \u2018Professor ID unknown\u2019, Professor name already \n" +
            "exists\u2019, \"Professor Data Invalid\".\n" +
            "2. The Registrar changes or corrects the data or modifies the fields properly.\n" +
            "3. The Registrar presses \u2018Save\u2019 again, or cancel the operation.\n" +
            "\n" +
            "Alternative scenario no. 2:\n" +
            "1. The Registrar repeats the first 5 steps for each Professor that the Registrar wishes to add to C-Reg. \n" +
            "\n" +
            "FP \u201cAdd a Professor\u2019s details\u201d measurement:\n" +
            "Triggering Event: The Registrar selects the sub-option \"Add Professor.\"\n" +
            "Functional User: Registrar\n" +
            "Sub-Processes:\n" +
            "MAIN SCENARIO:\n" +
            "1. When a Registrar wishes to enter data about a new Professor, he selects the sub-option 'Add Professor'.\n" +
            "    No data movement.\n" +
            "2. C-Reg displays a blank formatted screen for entry of Professor data.\n" +
            "    No data movement.\n" +
            "3. The Registrar enters the following details for the Professor: ID, name and address, social security number, Department, qualifications and \n" +
            "contact details.\n" +
            "    FU (Registrar) - DG (Professor details) \u2013 OOI (Professor) \u2013 Entry.\n" +
            "4. The Registrar presses \u2018Save\u2019.\n" +
            "    No data movement.\n" +
            "5. C-Reg validates the data to ensure the proper formats.\n" +
            "    No data movement.\n" +
            "6. C-Reg checks if the social security number is greater than 0.\n" +
            "    No data movement.\n" +
            "7. C-Reg checks whether a Professor of that ID already exists.\n" +
            "    DG (Professor ID) \u2013 OOI (Professor) \u2013 Read.\n" +
            "8. C-Reg creates a record for the new Professor.\n" +
            "    DG (Professor details) \u2013 OOI (Professor) \u2013 Write.\n" +
            "9. C-Reg displays a confirmation message.\n" +
            "    DG (Messages) \u2013 OOI (Message) \u2013 Exit. It must be counted only once.\n" +
            "\n" +
            "Alternative scenario no. 1:\n" +
            "1. If data entered is not valid, C-Reg displays one or more error messages, for example: \u2018Professor ID unknown\u2019, Professor name already \n" +
            "exists\u2019, \"Professor Data Invalid\".\n" +
            "    DG (Messages) \u2013 OOI (Message) \u2013 Exit. It must be counted only once.\n" +
            "2. The Registrar changes or corrects the data or modifies the fields properly.\n" +
            "    No data movement.\n" +
            "3. The Registrar presses \u2018Save\u2019 again, or cancel the operation.\n" +
            "    No data movement.\n" +
            "\n" +
            "Alternative scenario no. 2:\n" +
            "1. The Registrar repeats the first 5 steps for each Professor that the Registrar wishes to add to C-Reg. \n" +
            "    No data movement.\n" +
            "\n" +
            "###\n" +
            "\n" +
            "FP \u201cDelete a Professor\u2019s details\u201d:\n" +
            "MAIN SCENARIO:\n" +
            "1. The Registrar presses \u2018Delete\u2019 relating to the Professor of his (or her) interest.\n" +
            "2. C-Reg enquires on the Course Catalog whether the Professor has any Course Offerings that he has committed to teach. \n" +
            "3. The Course Catalog replies to C-Reg with a \u2018yes/no\u2019 indication.\n" +
            "4. If the Professor has no Course Offering teaching commitments, C-Reg displays a message asking\n" +
            "the Registrar to confirm the deletion.\n" +
            "5. If the Registrar selects \u2018yes\u2019, C-Reg deletes the Professor data.\n" +
            "\n" +
            "Alternative flow no. 1:\n" +
            "6. if the Registrar selects \u2018no\u2019, the operation is cancelled.\n" +
            "\n" +
            "Exceptions:\n" +
            "7. If the Professor is committed to teach any Course Offerings, deletion is not allowed, C-Reg displays an error message.\n" +
            "8. The Registrar must abandon the operation.\n" +
            "\n" +
            "FP \u201cDelete a Professor\u2019s details\u201d measurement:\n" +
            "Triggering Event: The Registrar presses \u2018Delete\u2019 relating to the Professor of his (or her) interest.\n" +
            "Functional User: Registrar\n" +
            "Sub-Processes:\n" +
            "MAIN SCENARIO:\n" +
            "1. The Registrar presses \u2018Delete\u2019 relating to the Professor of his (or her) interest.\n" +
            "    FU (Registrar) - DG (Professor ID) \u2013 OOI (Professor) \u2013 Entry.\n" +
            "2. C-Reg enquires on the Course Catalog whether the Professor has any Course Offerings that he has committed to teach. \n" +
            "    FU (Course Catalog) - DG (Professor ID) \u2013 OOI (Professor) \u2013 Exit.\n" +
            "3. The Course Catalog replies to C-Reg with a \u2018yes/no\u2019 indication.\n" +
            "    FU (Course Catalog) - DG (Professor\u2019s Course Offering) \u2013 OOI (Course Offerings) - Entry.\n" +
            "4. If the Professor has no Course Offering teaching commitments, C-Reg displays a message asking\n" +
            "the Registrar to confirm the deletion.\n" +
            "    No data movement.\n" +
            "5. If the Registrar selects \u2018yes\u2019, C-Reg deletes the Professor data.\n" +
            "    DG (Professor details) \u2013 OOI (Professor) \u2013 Write.\n" +
            "\n" +
            "Alternative flow no. 1:\n" +
            "6. if the Registrar selects \u2018no\u2019, the operation is cancelled.\n" +
            "    No data movement.\n" +
            "\n" +
            "Exceptions:\n" +
            "7. If the Professor is committed to teach any Course Offerings, deletion is not allowed, C-Reg displays an error message.\n" +
            "    DG (Messages) \u2013 OOI (Message) \u2013 Exit. It must be counted only once.\n" +
            "8. The Registrar must abandon the operation.\n" +
            "    No data movement.\n" +
            " \n" +
            "###\n" +
            "\n" +
            "FP \u201cSearch for ships\u201d:\n" +
            "MAIN SCENARIO:\n" +
            "1. The system timer ticks.\n" +
            "2. The actor presses the \u201cview ships\u201d link to list all the ships.\n" +
            "3. The system searches for all the ships in persistent storage.\n" +
            "4. The system deletes the previously rented ship.\n" +
            "5. The system displays the list of the ships.\n" +
            "6. The actor clicks \"Transform\" relating to the ship of his interest.\n" +
            "7. The system requests the Sep service to process the transformation.\n" +
            "8. The Sep service replies with the transformation.\n" +
            "\n" +
            "FP: \"Search for ships\" measurement:\n" +
            "Triggering Event: The system timer ticks.\n" +
            "Functional User: system timer\n" +
            "Sub-Processes:\n" +
            "MAIN SCENARIO:\n" +
            "1. The system timer ticks.\n" +
            "    FU (system timer) - DG (Clock tick) - OOI (Clock) - Entry.\n" +
            "2. The actor presses the \u201cview ships\u201d link to list all the ships.\n" +
            "    FU (actor) - DG (All ships) - OOI (Ship) - Entry.\n" +
            "3. The system searches for all the ships in persistent storage.\n" +
            "    DG (Ships details) - OOI (Ship) - Read.\n" +
            "4. The system deletes the previously rented ship.\n" +
            "    DG (Rented ships) - OOI (Ship) - Write.\n" +
            "5. The system displays the list of the ships.\n" +
            "    DG (Ships details) - OOI (Ship) - Exit.\n" +
            "6. The actor clicks \"Transform\" relating to the ship of his interest.\n" +
            "    FU (actor) - DG (Ship ID) - OOI (Ship) - Entry.\n" +
            "7. The system requests the Sep service to process the transformation.\n" +
            "    DG (Transformation) - OOI (Ship) - Exit.\n" +
            "8. The Sep service replies with the transformation.\n" +
            "    FU (Sep) - DG (Transformation) - OOI (Ship) - Entry.\n" +
            "\n" +
            "###\n" +
            "\n" +
            "FP \u201cReserve cabins\u201d:\n" +
            "MAIN SCENARIO:\n" +
            "1\tOperator\tThe actor presses \"Reserve cabins\" relating to the cabins of his interest.\n" +
            "2\tSystem\tThe system stores the reserved cabins.\n" +
            "3\tSystem\tThe system shows a confirmation message.\n" +
            "\n" +
            "Alternative flow no. 1\n" +
            "1.a1\tOperator\tThe user specifies the number of cabins.\n" +
            "2.a1\tSystem\tThe system present static drop-down lists to specify every cabin's seat. The flow of events resumes at step no. 1 of the main scenario.\n" +
            "\n" +
            "FP: \"Reserve cabins\" measurement:\n" +
            "Triggering Event: The actor presses \"Reserve cabins\" relating to the cabins of his interest.\n" +
            "Functional User: Operator\n" +
            "Sub-Processes:\n" +
            "MAIN SCENARIO:\n" +
            "1\tOperator\tThe actor presses \"Reserve cabins\" relating to the cabins of his interest.\n" +
            "    FU (Operator) - DG (Reservation details) - OOI (Reservation) - Entry.\n" +
            "2\tSystem\tThe system stores the reserved cabins.\n" +
            "    DG (Booked cabin details) - OOI (Booked cabin) - Write.\n" +
            "3\tSystem\tThe system shows a confirmation message.\n" +
            "    DG (Messages) \u2013 OOI (Message) \u2013 Exit. It must be counted only once.\n" +
            "\n" +
            "Alternative flow no. 1\n" +
            "1.a1\tOperator\tThe user specifies the number of cabins.\n" +
            "    No data movement.\n" +
            "2.a1\tSystem\tThe system present static drop-down lists to specify every cabin's seat. The flow of events resumes at step no. 1 of the main scenario.\n" +
            "    No data movement.\n" +
            "\n" +
            "###\n" +
            "\n" +
            "FP \u201cActivate sensors\u201d:\n" +
            "MAIN SCENARIO:\n" +
            "1.\tSwitch\tThe switch enters the \"activate sensor\" regarding a specific sensor.\n" +
            "2.\tSystem\tThe system read the Sensor internal state.\n" +
            "3.\tSensor\tThe system requests the Sensor to process the \"activate\" command.\n" +
            "4.\tMonitor\tThe Monitor displays the new Sensor state.\n" +
            "\n" +
            "FP: \"Activate sensors\" measurement:\n" +
            "Triggering Event: The switch enters the \"activate sensor\" regarding a specific sensor.\n" +
            "Functional User: Switch\n" +
            "Sub-Processes:\n" +
            "MAIN SCENARIO:\n" +
            "1.\tSwitch\tThe switch enters the \"activate sensor\" regarding a specific sensor.\n" +
            "    FU (Switch) - DG (Activate signal) - OOI (Sensor) - Entry.\n" +
            "2.\tSystem\tThe system read the Sensor internal state.\n" +
            "    DG (Sensor internal state) - OOI (Sensor) - Read.\n" +
            "3.\tSensor\tThe system requests the Sensor to process the \"activate\" command.\n" +
            "    FU (Sensor) - DG (Sensor internal state) - OOI (Sensor) - Exit.\n" +
            "4.\tMonitor\tThe Monitor displays the new Sensor state.\n" +
            "    FU (Monitor) - DG (Sensor state) - OOI (Sensor) - Exit.\n" +
            "\n" +
            "###\n" +
            "\n" +
            "FP \u201cActivate processes\u201d:\n" +
            "MAIN SCENARIO:\n" +
            "1.\tBG Process\tThe BG process sends the run command to the system.\n" +
            "2.\tSystem\tThe system responds sending the \"stop\" command to the OS Process.\n" +
            "3.\tSystem\tThe system sends the \"stop\" command to the SX Process.\n" +
            "\n" +
            "FP: \"Activate processes\" measurement:\n" +
            "Triggering Event: The BG process sends the run command to the system.\n" +
            "Functional User: BG Process\n" +
            "Sub-Processes:\n" +
            "MAIN SCENARIO:\n" +
            "1.\tBG Process\tThe BG process sends the run command to the system.\n" +
            "    FU (BG Process) - DG (Run command) - OOI (BG Process) - Entry.\n" +
            "2.\tSystem\tThe system responds sending the \"stop\" command to the OS Process.\n" +
            "    FU (OS Process) - DG (Stop command) - OOI (OS Process) - Exit.\n" +
            "3.\tSystem\tThe system sends the \"stop\" command to the SX Process.\n" +
            "    FU (SX Process) - DG (Stop command) - OOI (SX Process) - Exit.\n" +
            "\n" +
            "###\n" +
            "\n";

    /**
     * USER_COSMIC_MEASURE.format(uc_name=..., uc=...) di prompt.py:
     * {@code FP "<name>":\n<uc>\n\nFP: "<name>" measurement:}.
     * NOTA: in prompt.py l'ultima riga contiene il letterale "uc_name" invece del segnaposto {uc_name}
     * (typo evidente: tutti gli esempi few-shot e la Fig. 8 del paper CosMet usano il nome reale del FP),
     * qui viene quindi sostituito il nome vero.
     */
    public static String userMeasure(String ucName, String splitUc) {
        return "FP \"" + ucName + "\":\n" + splitUc + "\n\nFP: \"" + ucName + "\" measurement:";
    }

    // ---------------------------------------------------------------- Unica differenza rispetto a Python
    /**
     * Unica differenza rispetto allo script del professore: invece del report testuale
     * (che functions.print_analysis() parsa riga per riga) all'LLM si chiede lo STESSO risultato
     * come JSON, compatibile con {@code CosmicJsonModel}. Le regole di misura restano quelle
     * degli esempi few-shot del SYSTEM prompt.
     */
    public static final String JSON_OUTPUT_ADDENDUM =
            "\n\n###\n\n" +
            "OUTPUT FORMAT (this section overrides ONLY the output format shown in the examples above):\n" +
            "Apply EXACTLY the same measurement rules, the same Data Movement identification and the same reasoning shown in the examples above, " +
            "but do not write the textual report. Return ONLY one valid JSON object (no markdown fences, no comments, no explanation) with this structure:\n" +
            "{\n" +
            "  \"triggeringEvent\": \"<the Triggering Event>\",\n" +
            "  \"subProcesses\": [\n" +
            "    {\n" +
            "      \"step\": 1,\n" +
            "      \"description\": \"<the sentence, copied verbatim from the numbered list, without its number, without the actor column and without section titles>\",\n" +
            "      \"functionalUser\": \"<the FU of the data movement, or null>\",\n" +
            "      \"dataMovementType\": \"<E | X | R | W, or null when the sentence has no data movement>\",\n" +
            "      \"dataGroup\": \"<DG or null>\",\n" +
            "      \"objectOfInterest\": \"<OOI or null>\"\n" +
            "    }\n" +
            "  ]\n" +
            "}\n" +
            "Rules for the JSON:\n" +
            "- Write one sub-process object for EVERY numbered sentence, in the same order, and number \"step\" consecutively starting from 1.\n" +
            "- E = Entry, X = Exit, R = Read, W = Write. Use null (not a string) when the sentence has \"No data movement\".\n" +
            "- Error and confirmation messages: use dataMovementType \"X\", dataGroup \"Messages\" and objectOfInterest \"Message\" " +
            "(they are counted only once per functional process, exactly as in the examples).\n" +
            "- Use double quotes only and no trailing commas.\n";

    /** Promemoria aggiunto al messaggio utente (Step 2) per ancorare il formato JSON nonostante gli esempi few-shot testuali. */
    public static final String USER_JSON_SUFFIX =
            "\n\nReturn ONLY the JSON object described in the OUTPUT FORMAT section (no explanation, no markdown).";

    /** Variante del promemoria usata al secondo tentativo, quando la prima risposta non era parsabile. */
    public static final String USER_JSON_SUFFIX_STRICT =
            "\n\nIMPORTANT: your previous reply could not be parsed. Reply with the JSON object ONLY, " +
            "starting with '{' and ending with '}', with one entry in \"subProcesses\" for every numbered sentence.";

    // ---------------------------------------------------------------- MICRO-LOOP (v4.0): UNA frase per chiamata

    /**
     * v4.0 - Addendum dello Step 2 per il Micro-Loop. Gli esempi few-shot del professore (SYSTEM_COSMIC_MEASURE)
     * restano INTATTI: questa sezione cambia solo il COMPITO (una frase sola invece dell'intero processo) e il
     * formato di risposta (un oggetto JSON piccolo). L'LLM non puo' piu' "riassumere" perche' deve rispondere
     * su una sola frase; l'intero scenario e' presente solo come CONTESTO (serve a capire, ad esempio, che il
     * click iniziale e' l'Entry del processo e che l'errore e' un messaggio gia' contato).
     */
    public static final String MICRO_MEASURE_ADDENDUM =
            "\n\n###\n\n" +
            "TASK AND OUTPUT FORMAT (this section overrides ONLY the task and the output format shown in the examples above):\n" +
            "You will receive the complete numbered scenario of ONE functional process, but ONLY AS CONTEXT. One line of the scenario is marked " +
            "with '>>>' and repeated as TARGET SENTENCE. Measure ONLY the target sentence: do NOT measure, summarize or mention any other sentence. " +
            "Apply EXACTLY the same measurement rules, the same Data Movement identification and the same reasoning shown in the examples above.\n" +
            "Quick reminder of the rules used in the examples (they are not new rules):\n" +
            "- Entry (E): the functional user (a person or an external system) gives data to the system: enters or fills in data, selects/presses a button " +
            "\"relating to\" a specific item of interest (the identifier of the item enters), replies with data, a timer/event arrives with data.\n" +
            "- Exit (X): the system gives data to a functional user (displays, shows, lists, prints, sends a request or a command to another system). " +
            "Error, warning and confirmation messages are Exit with DG \"Messages\" and OOI \"Message\" (they are counted only once per functional process by the application).\n" +
            "- Read (R): the system retrieves data from persistent storage (searches, reads, looks up, checks whether something already exists or is occupied).\n" +
            "- Write (W): the system creates, updates, deletes or stores data in persistent storage.\n" +
            "- No data movement: navigation or clicks that carry no data (selecting a menu sub-option, pressing Save/Cancel/Continue), validations of data already " +
            "entered (formats, lengths, ranges), browsing, flow control (\"The flow of events ends/resumes ...\").\n" +
            "The scenario has already been split into atomic sentences: the target sentence has AT MOST ONE data movement.\n" +
            "Return ONLY one valid JSON object (no markdown fences, no comments, no explanation) with exactly this structure:\n" +
            "{\n" +
            "  \"functionalUser\": \"<the FU of the data movement, or null>\",\n" +
            "  \"dataMovementType\": \"<E | X | R | W, or null when the target sentence has \\\"No data movement\\\">\",\n" +
            "  \"dataGroup\": \"<DG or null>\",\n" +
            "  \"objectOfInterest\": \"<OOI or null>\"\n" +
            "}\n" +
            "E = Entry, X = Exit, R = Read, W = Write. Use null (not a string) for fields that do not apply. Use double quotes only and no trailing commas.\n";

    /** Prompt di sistema del Micro-Loop: esempi del professore + addendum a frase singola. */
    public static String systemMicroMeasure() {
        return SYSTEM_COSMIC_MEASURE + MICRO_MEASURE_ADDENDUM;
    }

    /**
     * Messaggio utente del Micro-Loop. Ordine voluto: la parte variabile sta in fondo, cosi' il prefisso
     * (prompt di sistema da ~4k token) resta identico tra le chiamate e LM Studio puo' riusare la cache del prompt.
     *
     * @param ucName         nome del functional process
     * @param numberedContext l'intero scenario numerato, con la riga target gia' marcata da '>>> '
     * @param targetIndex    numero (nel contesto) della frase target
     * @param target         testo della frase target
     */
    public static String userMeasureSentence(String ucName, String numberedContext, int targetIndex, String target) {
        return "FP \"" + ucName + "\":\n" + numberedContext + "\n\n" +
                "TARGET SENTENCE (line " + targetIndex + "): " + target + "\n\n" +
                "Measure ONLY the target sentence and return ONLY the JSON object.";
    }

    /** Promemoria del secondo tentativo (a temperature 0 lo stesso prompt darebbe la stessa risposta). */
    public static final String USER_MICRO_STRICT_SUFFIX =
            "\n\nIMPORTANT: your previous reply could not be parsed. Reply with the JSON object ONLY, starting with '{' and ending with '}', " +
            "with the keys functionalUser, dataMovementType, dataGroup, objectOfInterest. dataMovementType must be exactly one of \"E\", \"X\", \"R\", \"W\" or null.";

    /** Prompt di sistema completo dello Step 2 (SYSTEM_COSMIC_MEASURE del professore + addendum JSON). */
    public static String systemCosmicMeasureJson() {
        return SYSTEM_COSMIC_MEASURE + JSON_OUTPUT_ADDENDUM;
    }
}
