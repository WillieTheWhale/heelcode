# September 4th Research Meeting

## Meeting summary

### Quick recap

The meeting focused on discussing IRB requirements and data anonymization processes for various student research projects. Prasun instructed Mason to write up a proposal outlining all projects and their IRB needs, with particular attention to projects involving user studies like Rachel's work, while other projects involving log anonymization might not require full IRB approval. Mason presented findings from analyzing Comp 89 LLM logs, identifying multiple locations where personal information appears including file names, chat headers, and embedded content that would need anonymization. The team discussed creating a two-step anonymization approach using both rule-based systems and LLMs for handling unstructured data, with Sophia's taxonomy project serving as the primary focus for developing this anonymization framework. Prasun emphasized that research should complement coursework rather than interfere with academic performance, particularly noting that students not enrolled in 495 should balance research activities with their regular studies.

## Next steps

### Mason

- Write up a paragraph describing the projects (especially Sophia's taxonomy project) and whether they require IRB approval, focusing on anonymizing logs and processing data without user intervention.
- Reach out to the IRB office to discuss projects involving student data and anonymization, and clarify IRB requirements.
- Manually anonymize a few log files (replace names) and send them to Sophia for testing.
- Update quiz instructions to specify that only text files should be uploaded, and configure Google Drive to accept only text files.
- Give Nikhil and Shreyas access to the cleaned-up Piazza agent code GitHub repo.
- Forward the email to Mason Boyles' Gmail to obtain Ed discussion code.
- Work on the thesis proposal this semester, incorporating EICS rebuttals and research goals.

### Neal

- Follow up with Ethan to obtain bash logs for his project.

### Nikhil

- Test the Chrome extension study and review the GitHub repo sent by Prasun.
- Test answering questions using course documents and LLMs, and report findings by next week's meeting.
- Review Caltech's RAGMAN system and compare technical aspects to the team's goals.
- Explore using LangChain and LangGraph4J for RAG implementation.

### Prasun

- Send Neal information about SuperShell implementations and studies.

### Rachel

- Complete the IRB training using the correct link provided by Prasun.
- Access Sam's Jupyter notebook and review the papers sent by Prasun.
- Complete the web training study sent by Prasun to understand study formats.

### Sophia

- Use LLMs to write a program that finds and replaces names with pseudonyms in log files, using sample data provided by Mason.
- Investigate and potentially use existing packages or databases for name variations and abbreviations.

## Summary

### Student Project IRB Requirements

Prasun instructed Mason to write up ideas for all student projects, including whether user studies would be needed and whether IRB approval would be required. Prasun specifically advised against starting Rachel's project immediately as it would definitely require IRB approval due to involving user studies. The discussion concluded with Prasun finding and sharing a link for IRB training, which Rachel confirmed she needed to complete.

### Project Status and Training Updates

The meeting discussed ongoing projects and studies among team members. Prasun encouraged Rachel and Nikhil to complete a web training wheel study using a Chrome extension to understand study methodology. Rachel encountered issues accessing IRB training resources but found an alternative link. Sophia joined late and confirmed she is working on the taxonomy project involving log processing. Mason noted that only a few projects, including Sophia's and Neal's work with LLM logs, would involve student data.

### Office Hours Data Anonymization Strategy

Prasun suggested using Sophia's project as a driving force to analyze office hours discussion logs and build better tools by anonymizing the data. Mason presented findings from reviewing Comp 89 logs, identifying multiple locations where personal information appears including file names, chat headers, and references within the content. The team agreed to implement a two-step approach: first using rule-based methods to remove obvious identifying information, then using a local LLM model for a final pass to handle unstructured content, with Rachel noting the need for an IRB study due to handling human subjects data.

### Student Data Pseudonym Implementation

Prasun and Mason discussed implementing pseudonyms for student data to track progress over time and analyze question diversity across students. They agreed to make text files the only upload option for submissions to simplify data compilation, and Mason will update the quiz instructions to specify text file requirements. They also discussed using existing anonymizers for Piazza and Zoom logs, with Mason having access to both Bowen's previous thesis data and his own course data (524) for the taxonomy project.

### Research Projects and Next Steps

The team discussed several ongoing research projects and next steps. Mason agreed to manually anonymize a few logs to provide test data to Sophia and the team, and to submit IRB documentation in the form of a paragraph. Prasun advised the non-course students (Neal, Nikhil, and Sophia) to balance their research work with academic responsibilities. Nikhil and Shreyas were directed to explore LangChain's RAG implementation and test basic question-answering capabilities using existing course documents as prompts, rather than immediately focusing on complex RAG systems. Mason also mentioned he would be meeting with Shreyas next week to discuss Piazza agent code implementation.
