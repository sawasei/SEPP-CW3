package controller;

import external.AuthenticationService;
import external.EmailService;
import model.*;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.highlight.InvalidTokenOffsetsException;
import view.View;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class InquirerController extends Controller {
    public InquirerController(SharedContext sharedContext, View view, AuthenticationService auth, EmailService email) {
        super(sharedContext, view, auth, email);
    }

    public void consultFAQ() {
        FAQSection currentSection = null;
        String userEmail;
        if (sharedContext.currentUser instanceof AuthenticatedUser) {
            userEmail = ((AuthenticatedUser) sharedContext.currentUser).getEmail();
        } else {
            userEmail = null;
        }

        int optionNo = 0;
        while (currentSection != null || optionNo != -1) {
            if (currentSection == null) {
                view.displayFAQ(sharedContext.getFAQ());
                view.displayInfo("[-1] Return to main menu");
                view.displayInfo("[-2] Search FAQ");
            } else {
                view.displayFAQSection(currentSection);
                view.displayInfo("[-1] Return to " + (currentSection.getParent() == null ? "FAQ" : currentSection.getParent().getTopic()));

                if (userEmail == null) {
                    view.displayInfo("[-2] Request updates for this topic");
                    view.displayInfo("[-3] Stop receiving updates for this topic");
                } else {
                    if (sharedContext.usersSubscribedToFAQTopic(currentSection.getTopic()).contains(userEmail)) {
                        view.displayInfo("[-2] Stop receiving updates for this topic");
                    } else {
                        view.displayInfo("[-2] Request updates for this topic");
                    }
                }
            }

            String input = view.getInput("Please choose an option: ");

            try {
                optionNo = Integer.parseInt(input);

                if (currentSection == null && optionNo == -2) {
                    searchFAQ();
                    continue;
                }

                if (optionNo != -1 && optionNo != -2 && optionNo != -3) {
                    try {
                        if (currentSection == null) {
                            currentSection = sharedContext.getFAQ().getSections().get(optionNo);
                        } else {
                            currentSection = currentSection.getSubsections().get(optionNo);
                        }
                    } catch (IndexOutOfBoundsException e) {
                        view.displayError("Invalid option: " + optionNo);
                    }
                }

                if (currentSection != null) {
                    String topic = currentSection.getTopic();

                    if (userEmail == null && optionNo == -2) {
                        requestFAQUpdates(null, topic);
                    } else if (userEmail == null && optionNo == -3) {
                        stopFAQUpdates(null, topic);
                    } else if (optionNo == -2) {
                        if (sharedContext.usersSubscribedToFAQTopic(topic).contains(userEmail)) {
                            stopFAQUpdates(userEmail, topic);
                        } else {
                            requestFAQUpdates(userEmail, topic);
                        }
                    } else if (optionNo == -1) {
                        currentSection = currentSection.getParent();
                        optionNo = 0;
                    }
                }
            } catch (NumberFormatException e) {
                view.displayError("Invalid option: " + input);
            }
        }
    }

    private void requestFAQUpdates(String userEmail, String topic) {
        if (userEmail == null) {
            userEmail = view.getInput("Please enter your email address: ");
        }
        boolean success = sharedContext.registerForFAQUpdates(userEmail, topic);
        if (success) {
            view.displaySuccess("Successfully registered " + userEmail + " for updates on '" + topic + "'");
        } else {
            view.displayError("Failed to register " + userEmail + " for updates on '" + topic + "'. Perhaps this email was already registered?");
        }
    }

    private void stopFAQUpdates(String userEmail, String topic) {
        if (userEmail == null) {
            userEmail = view.getInput("Please enter your email address: ");
        }
        boolean success = sharedContext.unregisterForFAQUpdates(userEmail, topic);
        if (success) {
            view.displaySuccess("Successfully unregistered " + userEmail + " for updates on '" + topic + "'");
        } else {
            view.displayError("Failed to unregister " + userEmail + " for updates on '" + topic + "'. Perhaps this email was not registered?");
        }
    }
    
    private void searchFAQ() {
        String keyword = view.getInput("Enter search keyword: ").toLowerCase();
        if (keyword.strip().isEmpty()) {
            view.displayWarning("Search keyword cannot be empty!");
            return;
        }
        
        view.displayInfo("Search results for '" + keyword + "':");
        view.displayDivider();
        
        boolean foundResults = false;
        List<FAQSection> allSections = new ArrayList<>();
        List<FAQItem> matchingItems = new ArrayList<>();
        
        // Add all root sections
        allSections.addAll(sharedContext.getFAQ().getSections());
        
        // Process all sections and their subsections
        for (int i = 0; i < allSections.size(); i++) {
            FAQSection section = allSections.get(i);
            // Add subsections to be processed
            allSections.addAll(section.getSubsections());
            
            // Check items in this section
            for (FAQItem item : section.getItems()) {
                if (item.getQuestion().toLowerCase().contains(keyword) || 
                    item.getAnswer().toLowerCase().contains(keyword)) {
                    foundResults = true;
                    matchingItems.add(item);
                    view.displayInfo("Topic: " + section.getTopic());
                    view.displayInfo("Q: " + item.getQuestion());
                    view.displayInfo("> " + item.getAnswer());
                    view.displayDivider();
                }
            }
        }
        
        if (!foundResults) {
            view.displayInfo("No results found for '" + keyword + "'");
        } else {
            view.displayInfo("Found " + matchingItems.size() + " matching FAQ items");
        }
        
        view.getInput("Press Enter to continue...");
    }

    public void contactStaff() {
        String inquirerEmail;
        if (sharedContext.currentUser instanceof AuthenticatedUser) {
            AuthenticatedUser user = (AuthenticatedUser) sharedContext.currentUser;
            inquirerEmail = user.getEmail();
        } else {
            inquirerEmail = view.getInput("Enter your email address: ");
            // From https://owasp.org/www-community/OWASP_Validation_Regex_Repository
            if (!inquirerEmail.matches("^[a-zA-Z0-9_+&*-]+(?:\\.[a-zA-Z0-9_+&*-]+)*@(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,7}$")) {
                view.displayError("Invalid email address! Please try again");
                return;
            }
        }

        String subject = view.getInput("Describe the topic of your inquiry in a few words: ");
        if (subject.strip().isBlank()) {
            view.displayError("Inquiry subject cannot be blank! Please try again");
            return;
        }

        String text = view.getInput("Write your inquiry:" + System.lineSeparator());
        if (text.strip().isBlank()) {
            view.displayError("Inquiry content cannot be blank! Please try again");
            return;
        }

        Inquiry inquiry = new Inquiry(inquirerEmail, subject, text);
        sharedContext.inquiries.add(inquiry);

        email.sendEmail(
                SharedContext.ADMIN_STAFF_EMAIL,
                SharedContext.ADMIN_STAFF_EMAIL,
                "New inquiry from " + inquirerEmail,
                "Subject: " + subject + System.lineSeparator() + "Please log into the Self Service Portal to review and respond to the inquiry."
        );
        view.displaySuccess("Your inquiry has been recorded. Someone will be in touch via email soon!");
    }
}
