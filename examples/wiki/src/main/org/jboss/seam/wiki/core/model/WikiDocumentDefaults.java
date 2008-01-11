package org.jboss.seam.wiki.core.model;

import org.jboss.seam.wiki.core.engine.WikiMacro;

import java.util.List;

public class WikiDocumentDefaults {

    public String getName() {
        return "New Document";
    }

    public String getContentText() {
        return "Edit this text...";
    }

    /**
     * @return a list of <tt>WikiMacro</tt> instances or null if <tt>getHeaderMacrosAsString()</tt> should be called.
     */
    public List<WikiMacro> getContentMacros() {
        return null;
    }

    public String[] getContentMacrosAsString() {
        return new String[0];
    }

    public String getHeaderText() {
        return null;
    }

    /**
     * @return a list of <tt>WikiMacro</tt> instances or null if <tt>getHeaderMacrosAsString()</tt> should be called.
     */
    public List<WikiMacro> getHeaderMacros() {
        return null;
    }

    public String[] getHeaderMacrosAsString() {
        return new String[0];
    }

    public String getFooterText() {
        return null;
    }

    /**
     * @return a list of <tt>WikiMacro</tt> instances or null if <tt>getHeaderMacrosAsString()</tt> should be called.
     */
    public List<WikiMacro> getFooterMacros() {
        return null;
    }

    public String[] getFooterMacrosAsString() {
        return new String[0];
    }

    public void setOptions(WikiDocument document) {}

}
