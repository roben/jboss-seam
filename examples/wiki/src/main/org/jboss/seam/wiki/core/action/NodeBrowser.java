/*
 * JBoss, Home of Professional Open Source
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jboss.seam.wiki.core.action;

import org.jboss.seam.annotations.*;
import org.jboss.seam.annotations.Observer;
import org.jboss.seam.annotations.web.RequestParameter;
import org.jboss.seam.ScopeType;
import org.jboss.seam.Component;
import org.jboss.seam.faces.FacesMessages;
import org.jboss.seam.faces.FacesManager;
import org.jboss.seam.log.Log;
import org.jboss.seam.contexts.Contexts;
import org.jboss.seam.core.*;
import org.jboss.seam.core.Conversation;
import org.jboss.seam.security.Identity;
import org.jboss.seam.wiki.core.dao.NodeDAO;
import org.jboss.seam.wiki.core.model.*;
import org.jboss.seam.wiki.core.search.WikiSearch;
import org.jboss.seam.wiki.util.WikiUtil;

import java.util.*;
import java.io.Serializable;

/**
 * Resolves <tt>currentDocument</tt> and <tt>currentDirectory</tt> objects for given request parameters.
 * <p>
 * URLs typically mapped and resolved with these classes:
 * <p>
 * <pre>
 * http://host/         -- rewrite filter --> http://host/context/display.seam
 * http://host/123.html -- rewrite filter --> http://host/context/display.seam?nodeId=123
 * http://host/Foo      -- rewrite filter --> http://host/context/display.seam?areaName=Foo
 * http://host/Foo/Bar  -- rewrite filter --> http://host/context/display.seam?areaName=Foo&nodeName=Bar
 * </pre>
 * 'Foo' is a WikiName of a directory with a parentless parent (ROOT), we call this a logical area.
 * 'Bar' is a WikiName of a node in that logical area, unique within that area subtree.
 * <p>
 * We _never_ have URLs like <tt>http://host/Foo/Baz/Bar</tt> because 'Baz' would be a subdirectory
 * we don't need. An area name and a node name is enough, the node name is unique within
 * a subtree. We also never have <tt>http://host/Bar</tt>, a node name alone is not enough to
 * identify a node, we also need the area name.
 *
 * @author Christian Bauer
 */
@Name("browser")
@Scope(ScopeType.PAGE)
@AutoCreate
public class NodeBrowser implements Serializable {

    @Logger static Log log;

    @RequestParameter
    protected String areaName;

    @RequestParameter
    protected String nodeName;

    @RequestParameter
    protected String lastConversationId;

    protected Long nodeId;
    public Long getNodeId() { return nodeId; }
    public void setNodeId(Long nodeId) { this.nodeId = nodeId; }

    @In
    protected org.jboss.seam.faces.Redirect redirect;

    @In
    protected NodeDAO nodeDAO;

    // These are only EVENT scoped, we don't want them to jump from DocumentBrowser to
    // DirectoryBrowser over redirects
    @Out(scope = ScopeType.PAGE, required = false)
    protected Document currentDocument;

    @Out(scope = ScopeType.PAGE, required = false)
    protected Directory currentDirectory;

    @Out(scope = ScopeType.EVENT)
    protected  List<Node> currentDirectoryPath = new ArrayList<Node>();

    /**
     * Executes a redirect to the last view-id that was prepare()ed.
     * <p>
     * Usually called after ending a conversation. Assumes that the caller of the method does not want to propagate
     * the current (ended) conversation across that redirect. Also removes any stored <tt>actionOutcome</tt>,
     * <tt>actionMethod</tt> or <tt>cid</tt> request parameter before redirecting, we don't want to redirect to
     * a prepare()ed page that was in a long-running conversation (temporary doesn't matter) or that was last
     * called with an action (that action would probably send us straight back into the conversation we are trying
     * to redirect out of).
     *
     * TODO: These methods and the whole navigation strategy can be simplified with http://jira.jboss.com/jira/browse/JBSEAM-906
     */
    public void redirectToLastBrowsedPage() {

        // We don't want to redirect to an action, so if the last browsed page was called with an action, remove it
        redirect.getParameters().remove("actionOutcome");
        redirect.getParameters().remove("actionMethod");

        // If the last browsed page had a conversation identifier (we assume of a temporary conversation), remove it
        redirect.getParameters().remove("cid");

        // We also don't want to redirect the long-running conversation, the caller has ended it already
        redirect.setConversationPropagationEnabled(false);

        // This is not needed again
        redirect.getParameters().remove("lastConversationId");

        redirect.returnToCapturedView();
    }

    public void redirectToLastBrowsedPageWithConversation() {
        // We don't want to redirect to an action, so if the last browsed page was called with an action, remove it
        redirect.getParameters().remove("actionOutcome");
        redirect.getParameters().remove("actionMethod");

        // If the last browsed page had a conversation identifier (we assume of a temporary conversation), remove it
        redirect.getParameters().remove("cid");

        // This is not needed again
        redirect.getParameters().remove("lastConversationId");

        redirect.returnToCapturedView();
    }

    // TODO: Typical exit method to get out of a root, nested, or parallel root conversation, JBSEAM-906
    public void exitConversation(Boolean endBeforeRedirect) {
        Conversation currentConversation = Conversation.instance();
        if (currentConversation.isNested()) {
            // End this nested conversation and return to last rendered view-id of parent
            log.debug("ending current conversation and redirecting to last view of the parent conversation");
            currentConversation.endAndRedirect(endBeforeRedirect);
        } else {
            // Always end this conversation
            log.debug("ending conversation");
            currentConversation.end();

            ConversationEntry entryPoint =
                    (ConversationEntry)Contexts.getConversationContext().get("conversationEntryPoint");
            if (entryPoint != null) {
                log.debug("entry-point of this conversation (" + currentConversation.getId() + ") has been another conversation");
                if (entryPoint.isDisplayable()) {

                    // Get messages for propagation
                    FacesMessages messages = (FacesMessages)Component.getInstance("facesMessages");

                    log.debug("switching to entry-point conversation: " + entryPoint.getId());
                    FacesManager.instance().switchConversation(entryPoint.getId());

                    log.debug("propagating faces messages from the ended conversation into the destination conversation");
                    Contexts.getConversationContext().set("org.jboss.seam.faces.facesMessages", messages);

                    log.debug("switching to last view in previous conversation");
                    Conversation.instance().redirect();

                } else {
                    log.debug("the entry-point of this conversation is gone, redirecting to wiki start page");
                    FacesManager.instance().redirect("/display.xhtml", new HashMap<String,Object>(), true);
                }
            } else {
                log.debug("entry-point of this conversation (" + currentConversation.getId() + ") has been a non-conversational page we remembered");
                if (endBeforeRedirect) {
                    log.debug("redirecting to last browsed page without propagating the ended conversation");
                    redirectToLastBrowsedPage();
                } else {
                    log.debug("redirecting to last browsed page and propagating the ended conversation across redirect");
                    redirectToLastBrowsedPageWithConversation();
                }
            }
        }
    }

    // Just a convenience method for recursive calling
    protected void addDirectoryToPath(List<Node> path, Node directory) {
        if (Identity.instance().hasPermission("Node", "read", directory) ||
            directory.getId().equals( ((Directory)Component.getInstance("wikiRoot")).getId() )
           )
            path.add(directory);
        if (directory.getParent() != null )
            addDirectoryToPath(path, directory.getParent());
    }

    public void captureConversationEntryPoint() {
        if (lastConversationId != null) {
            ConversationEntries allConversations = ConversationEntries.instance();
            if (allConversations.getConversationEntry(lastConversationId) != null) {
                Contexts.getConversationContext()
                        .set("conversationEntryPoint", allConversations.getConversationEntry(lastConversationId));
            }
        }
    }

    @Observer("NodeBrowser.prepare")
    @Transactional
    public String prepare() {

        // Store the view-id that called this method (as a page action) for return (exit of a later conversation)
        redirect.captureCurrentRequest();
        // TODO: I'm not using captureCurrentView() because it starts a conversation (and it doesn't capture all request parameters)

        // Have we been called with a nodeId request parameter, could be document or directory
        if (nodeId != null) {

            // Try to find a document
            currentDocument = nodeDAO.findDocument(nodeId);

            // Document not found, see if it is a directory
            if (currentDocument == null) {
                currentDirectory = nodeDAO.findDirectory(nodeId);

                // Try to get a default document of that directory
                currentDocument = nodeDAO.findDefaultDocument(currentDirectory);

            } else {
                // Document found, take its directory
                currentDirectory = (Directory)currentDocument.getParent();
            }

        // Have we been called with an areaName and nodeName request parameter
        } else if (areaName != null && nodeName != null) {

            // Try to find the area
            Directory area = nodeDAO.findArea(areaName);
            if (area != null) {
                Node node = nodeDAO.findNodeInArea(area.getAreaNumber(), nodeName);
                if (WikiUtil.isDirectory(node)) {
                    currentDirectory = (Directory)node;
                    currentDocument = nodeDAO.findDefaultDocument(currentDirectory);
                 } else {
                    currentDocument = (Document)node;
                    currentDirectory = currentDocument != null ? currentDocument.getParent() : area;
                }
            }

        // Or have we been called just with an areaName request parameter
        } else if (areaName != null) {
            currentDirectory = nodeDAO.findArea(areaName);
            currentDocument = nodeDAO.findDefaultDocument(currentDirectory);
        }

        // Fall back, take the area name as a search query
        if (currentDirectory == null) {
            boolean foundMatches = false;
            if (areaName != null && areaName.length() > 0) {
                log.debug("searching for unknown area name: " + areaName);
                WikiSearch wikiSearch = (WikiSearch)Component.getInstance("wikiSearch");
                wikiSearch.setSimpleQuery(areaName);
                wikiSearch.search();
                foundMatches = wikiSearch.getTotalCount() > 0;
            }
            if (foundMatches) {
                return "search";
            } else {
                // Fall back to default document
                currentDocument = (Document)Component.getInstance("wikiStart");
                currentDirectory = currentDocument.getParent();
            }
        }

        // Set the id for later
        nodeId = currentDocument != null ? currentDocument.getId() : currentDirectory.getId();

        // Prepare directory path for breadcrumb
        currentDirectoryPath.clear();
        addDirectoryToPath(currentDirectoryPath, currentDirectory);
        Collections.reverse(currentDirectoryPath);

        // Return not-null outcome so we can navigate from here
        return "prepared";
    }

}
