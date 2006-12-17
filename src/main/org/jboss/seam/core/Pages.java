package org.jboss.seam.core;

import static org.jboss.seam.InterceptionType.NEVER;
import static org.jboss.seam.annotations.Install.BUILT_IN;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import javax.faces.application.ViewHandler;
import javax.faces.context.FacesContext;
import javax.faces.convert.Converter;

import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;
import org.jboss.seam.Component;
import org.jboss.seam.ScopeType;
import org.jboss.seam.annotations.Create;
import org.jboss.seam.annotations.FlushModeType;
import org.jboss.seam.annotations.Install;
import org.jboss.seam.annotations.Intercept;
import org.jboss.seam.annotations.Name;
import org.jboss.seam.annotations.Scope;
import org.jboss.seam.contexts.Contexts;
import org.jboss.seam.core.Expressions.MethodBinding;
import org.jboss.seam.core.Expressions.ValueBinding;
import org.jboss.seam.core.Page.PageParameter;
import org.jboss.seam.log.LogProvider;
import org.jboss.seam.log.Logging;
import org.jboss.seam.util.DTDEntityResolver;
import org.jboss.seam.util.Parameters;
import org.jboss.seam.util.Resources;

/**
 * Holds metadata for pages defined in pages.xml, including
 * page actions and page descriptions.
 * 
 * @author Gavin King
 */
@Scope(ScopeType.APPLICATION)
@Intercept(NEVER)
@Name("org.jboss.seam.core.pages")
@Install(precedence=BUILT_IN)
public class Pages extends Navigator
{
   
   private static final LogProvider log = Logging.getLogProvider(Pages.class);
   
   private Map<String, Page> pagesByViewId = Collections.synchronizedMap( new HashMap<String, Page>() );   
   private Map<String, List<Page>> pageStacksByViewId = Collections.synchronizedMap( new HashMap<String, List<Page>>() );   
   private String noConversationViewId;
   
   private SortedSet<String> wildcardViewIds = new TreeSet<String>( 
         new Comparator<String>() {
            public int compare(String x, String y)
            {
               if ( x.length()<y.length() ) return -1;
               if ( x.length()> y.length() ) return 1;
               return x.compareTo(y);
            }
         } 
      );
   
   @Create
   public void initialize()
   {
      InputStream stream = Resources.getResourceAsStream("/WEB-INF/pages.xml");      
      if (stream==null)
      {
         log.info("no pages.xml file found");
      }
      else
      {
         log.info("reading pages.xml");
         parse(stream);
      }
   }

   private void parse(InputStream stream)
   {
      Element root = getDocumentRoot(stream);
      if (noConversationViewId==null) //let the setting in components.xml override the pages.xml
      {
         noConversationViewId = root.attributeValue("no-conversation-view-id");
      }
      List<Element> elements = root.elements("page");
      for (Element page: elements)
      {
         parse( page, page.attributeValue("view-id") );
      } 
   }

   private void parse(InputStream stream, String viewId)
   {
      parse( getDocumentRoot(stream), viewId );
   }

   private Element getDocumentRoot(InputStream stream)
   {
      Document doc;
      SAXReader saxReader = new SAXReader();
      saxReader.setEntityResolver( new DTDEntityResolver() );
      saxReader.setMergeAdjacentText(true);
      try
      {
         doc = saxReader.read(stream);
      }
      catch (DocumentException de)
      {
         throw new RuntimeException(de);
      }
      Element root = doc.getRootElement();
      return root;
   }

   private void parse(Element element, String viewId)
   {
      Page page = parsePage(element, viewId);
      
      List<Element> children = element.elements("param");
      for (Element param: children)
      {
         page.getPageParameters().add( parsePageParameter(param) );
      }
      
      List<Element> moreChildren = element.elements("navigation");
      for (Element fromAction: moreChildren)
      {
         parseNavigation(page, fromAction);
      }
   }

   private Page parsePage(Element element, String viewId)
   {
      if ( viewId.endsWith("*") )
      {
         wildcardViewIds.add(viewId);
      }
      Page page = new Page(viewId);
      pagesByViewId.put(viewId, page);
      
      page.setSwitchEnabled( !"disabled".equals( element.attributeValue("switch") ) );
      
      Element optionalElement = element.element("description");
      String description = optionalElement==null ? 
               element.getTextTrim() : optionalElement.getTextTrim();
      if (description!=null && description.length()>0)
      {
         page.setDescription(description);
      }
      
      String timeoutString = element.attributeValue("timeout");
      if (timeoutString!=null)
      {
         page.setTimeout(Integer.parseInt(timeoutString));
      }
      
      page.setNoConversationViewId( element.attributeValue("no-conversation-view-id") );
      page.setConversationRequired( "true".equals( element.attributeValue("conversation-required") ) );
      
      String action = element.attributeValue("action");
      if (action!=null)
      {
         if ( action.startsWith("#{") )
         {
            MethodBinding methodBinding = Expressions.instance().createMethodBinding(action);
            page.setAction(methodBinding);
         }
         else
         {
            page.setOutcome(action);
         }
      }
      
      Element endConversation = element.element("end-conversation");
      if ( endConversation!=null )
      {
         page.setEndConversation(true);
      }
      
      Element beginConversation = element.element("begin-conversation");
      if ( beginConversation!=null )
      {
         page.setBeginConversation(true);
         page.setJoin( "true".equals( beginConversation.attributeValue("join") ) );
         page.setNested( "true".equals( beginConversation.attributeValue("nested") ) );
         page.setPageflow( beginConversation.attributeValue("pageflow") );
         String flushMode = beginConversation.attributeValue("flush-mode");
         if (flushMode!=null)
         {
            page.setFlushMode( FlushModeType.valueOf( flushMode.toUpperCase() ) );
         }
      }
      
      if ( page.isBeginConversation() && page.isEndConversation() )
      {
         throw new IllegalStateException("cannot use both <begin-conversation/> and <end-conversation/>");
      }
      
      String bundle = element.attributeValue("bundle");
      if (bundle!=null)
      {
         page.setResourceBundleName(bundle);
      }
      return page;
   }

   private void parseNavigation(Page entry, Element element)
   {
      Page.Navigation navigation = new Page.Navigation(); 
      String outcomeExpression = element.attributeValue("outcome");
      if (outcomeExpression!=null)
      {
         navigation.setOutcomeValueBinding(Expressions.instance().createValueBinding(outcomeExpression));
      }
      List<Element> cases = element.elements("case");
      for (Element childElement: cases)
      {
         Page.Case caze = parseCase(childElement);
         navigation.getCases().put( childElement.attributeValue("outcome"), caze );
      }
      Element childElement = element.element("default");
      if (childElement!=null)
      {
         navigation.setDefaultCase(parseCase(childElement));
      }
      
      String expression = element.attributeValue("action");
      if (expression==null)
      {
         entry.setDefaultNavigation(navigation);
      }
      else
      {
         entry.getNavigations().put(expression, navigation);
      }
   }

   private Page.PageParameter parsePageParameter(Element element)
   {
      String valueExpression = element.attributeValue("value");
      String name = element.attributeValue("name");
      if (name==null)
      {
         if (valueExpression==null)
         {
            throw new IllegalArgumentException("must specify name or value for page <param/> declaration");
         }
         name = valueExpression.substring(2, valueExpression.length()-1);
      }
      Page.PageParameter pageParameter = new Page.PageParameter(name);
      if (valueExpression!=null)
      {
         pageParameter.setValueBinding(Expressions.instance().createValueBinding(valueExpression));
      }
      pageParameter.setConverterId(element.attributeValue("converterId"));
      String converterExpression = element.attributeValue("converter");
      if (converterExpression!=null)
      {
         pageParameter.setConverterValueBinding(Expressions.instance().createValueBinding(converterExpression));
      }
      return pageParameter;
   }

   private Page.Case parseCase(Element element)
   {
      Page.Case caze = new Page.Case();
      Element render = element.element("render");
      if (render!=null)
      {
         final String viewId = render.attributeValue("view-id");
         caze.setResult(new Page.Result() {
            public void navigate(FacesContext context)
            {
               render(viewId);
            }
         });
      }
      Element redirect = element.element("redirect");
      if (redirect!=null)
      {
         List<Element> children = redirect.elements("param");
         final List<PageParameter> pageParameters = new ArrayList<PageParameter>();
         for (Element child: children)
         {
            pageParameters.add( parsePageParameter(child) );
         }
         final String viewId = redirect.attributeValue("view-id");
         caze.setResult(new Page.Result() {
            public void navigate(FacesContext context)
            {
               Map<String, Object> parameters = new HashMap<String, Object>();
               for ( PageParameter pageParameter: pageParameters )
               {
                  parameters.put( pageParameter.getName(), getParameterValue(context, pageParameter) );
               }
               redirect(viewId, parameters);
            }
         });
      }
      return caze;
   }

   public boolean navigate(FacesContext context, String actionExpression, final String actionOutcome)
   {
      String viewId = context.getViewRoot().getViewId();
      if (viewId!=null)
      {
         List<Page> stack = getPageStack(viewId);
         for (int i=stack.size()-1; i>=0; i--)
         {
            Page page = stack.get(i);
            Page.Navigation navigation = page.getNavigations().get(actionExpression);
            if (navigation==null)
            {
               navigation = page.getDefaultNavigation();
            }
            
            if (navigation!=null)
            {
               
               String outcome;
               if ( navigation.getOutcomeValueBinding()==null )
               {
                  outcome = actionOutcome;
               }
               else
               {
                  Object value = navigation.getOutcomeValueBinding().getValue();
                  outcome = value==null ? null : value.toString();
               }
               
               Page.Case caze = outcome==null ?
                  //JSF navhandler says ignore all rules when null outcome
                  navigation.getDefaultCase() :
                  navigation.getCases().get(outcome);
               if (caze!=null)
               {
                  //TODO: begin/end conversation, etc!!
                  caze.getResult().navigate(context);
                  return true;
               }
               
            }
            
         }
      }
      return false;
   }
   
   /**
    * Get the Page object for the given view id.
    * 
    * @param viewId a JSF view id
    */
   public Page getPage(String viewId)
   {
      if (viewId==null)
      {
         //for tests
         return new Page(viewId);
      }
      else
      {
         Page result = getCachedPage(viewId);
         if (result==null)
         {
            return createPage(viewId);
         }
         else
         {
            return result;
         }
      }
   }

   private Page createPage(String viewId)
   {
      String resourceName = replaceExtension(viewId, ".page.xml");
      InputStream stream = resourceName==null ? 
            null : Resources.getResourceAsStream( resourceName.substring(1) );
      if ( stream==null ) 
      {
         Page result = new Page(viewId);
         pagesByViewId.put(viewId, result);
         return result;
      }
      else
      {
         parse(stream, viewId);
         return getCachedPage(viewId);
      }
   }

   private Page getCachedPage(String viewId)
   {
      Page result = pagesByViewId.get(viewId);
      if (result==null)
      {
         //workaround for what I believe is a bug in the JSF RI
         viewId = replaceExtension( viewId, getSuffix() );
         if (viewId!=null)
         {
            result = pagesByViewId.get( viewId );
         }
      }
      return result;
   }
   
   private static String replaceExtension(String viewId, String suffix)
   {
      int loc = viewId.lastIndexOf('.');
      return loc<0 ? null : viewId.substring(0, loc) + suffix;
   }
   
   /**
    * Get the stack of Page objects, from least specific to 
    * most specific, that match the given view id.
    * 
    * @param viewId a JSF view id
    */
   protected List<Page> getPageStack(String viewId)
   {
      List<Page> stack = pageStacksByViewId.get(viewId);
      if (stack==null)
      {
         stack = createPageStack(viewId);
         pageStacksByViewId.put(viewId, stack);
      }
      return stack;
   }

   private List<Page> createPageStack(String viewId)
   {
      List<Page> stack = new ArrayList<Page>(1);
      if (viewId!=null)
      {
         for (String wildcard: wildcardViewIds)
         {
            if ( viewId.startsWith( wildcard.substring(0, wildcard.length()-1) ) )
            {
               stack.add( getPage(wildcard) );
            }
         }
      }
      Page page = getPage(viewId);
      if (page!=null) stack.add(page);
      return stack;
   }
   
   /**
    * Call page actions, from most general view id to most specific
    */
   public boolean callActions(FacesContext facesContext)
   {
      boolean result = false;
      String viewId = facesContext.getViewRoot().getViewId();
      for ( Page page: getPageStack(viewId) )
      {
         if ( page.isConversationRequired() && !Manager.instance().isLongRunningConversation() )
         {
            Manager.instance().redirectToNoConversationView();
            return result;
         }
         else
         {
            result = callAction(page, facesContext) || result;
         }
      }
      return result;
   }

   private boolean callAction(Page page, FacesContext facesContext)
   {
      boolean result = false;
      
      page.beginOrEndConversation();

      String outcome = page.getOutcome();
      String fromAction = outcome;
      
      if (outcome==null)
      {
         MethodBinding methodBinding = page.getAction();
         if (methodBinding!=null) 
         {
            fromAction = methodBinding.getExpressionString();
            result = true;
            outcome = toString( methodBinding.invoke() );
         }
      }
      
      if (outcome!=null)
      {
         handleOutcome(facesContext, outcome, fromAction);
      }
      
      return result;

   }

   private static String toString(Object returnValue)
   {
      return returnValue == null ? null : returnValue.toString();
   }

   private static void handleOutcome(FacesContext facesContext, String outcome, String fromAction)
   {
      /*if (outcome!=null)
      {*/
         facesContext.getApplication().getNavigationHandler()
               .handleNavigation(facesContext, fromAction, outcome);
      //}
   }
   
   public static Pages instance()
   {
      if ( !Contexts.isApplicationContextActive() )
      {
         throw new IllegalStateException("No active application context");
      }
      return (Pages) Component.getInstance(Pages.class, ScopeType.APPLICATION);
   }

   /**
    * Call the action requested by s:link or s:button.
    */
   public static boolean callAction(FacesContext facesContext)
   {
      //TODO: refactor with Pages.instance().callAction()!!
      
      boolean result = false;
      
      String outcome = (String) facesContext.getExternalContext()
            .getRequestParameterMap()
            .get("actionOutcome");
      String fromAction = outcome;
      
      if (outcome==null)
      {
         String actionId = (String) facesContext.getExternalContext()
               .getRequestParameterMap()
               .get("actionMethod");
         if (actionId!=null)
         {
            if ( !SafeActions.instance().isActionSafe(actionId) ) return result;
            String expression = SafeActions.toAction(actionId);
            result = true;
            MethodBinding actionBinding = Expressions.instance().createMethodBinding(expression);
            outcome = toString( actionBinding.invoke() );
            fromAction = expression;
            handleOutcome(facesContext, outcome, fromAction);
         }
      }
      else
      {
         handleOutcome(facesContext, outcome, fromAction);
      }
      
      return result;
   }
   
   /**
    * Build a list of page-scoped resource bundles, from most
    * specific view id, to most general.
    */
   public List<ResourceBundle> getResourceBundles(String viewId)
   {
      List<ResourceBundle> result = new ArrayList<ResourceBundle>(1);
      List<Page> stack = getPageStack(viewId);
      for (int i=stack.size()-1; i>=0; i--)
      {
         Page page = stack.get(i);
         ResourceBundle bundle = page.getResourceBundle();
         if ( bundle!=null ) result.add(bundle);
      }
      return result;
   }
   
   /**
    * Get the values of any page parameters by evaluating the value bindings
    * against the model and converting to String.
    * 
    * @param viewId the JSF view id
    * @return a map of page parameter name to String value
    */
   public Map<String, Object> getConvertedParameters(FacesContext facesContext, String viewId)
   {
      return getConvertedParameters(facesContext, viewId, Collections.EMPTY_SET);
   }
   
   /**
    * Get the values of any page parameters by evaluating the value bindings
    * against the model.
    * 
    * @param viewId the JSF view id
    * @return a map of page parameter name to value
    */
   protected Map<String, Object> getParameters(String viewId)
   {
      Map<String, Object> parameters = new HashMap<String, Object>();
      for ( Page page: getPageStack(viewId) )
      {
         for ( Page.PageParameter pageParameter: page.getPageParameters() )
         {
            ValueBinding valueBinding = pageParameter.getValueBinding();
            Object value;
            if (valueBinding==null)
            {
               value = Contexts.getPageContext().get( pageParameter.getName() );
            }
            else
            {
               value = valueBinding.getValue();
            }
            if (value!=null)
            {
               parameters.put( pageParameter.getName(), value );
            }
         }
      }
      return parameters;
   }
   
   /**
    * Get the values of any page parameters by evaluating the value bindings
    * against the model and converting to String.
    * 
    * @param viewId the JSF view id
    * @param overridden override certain parameter values
    * @return a map of page parameter name to String value
    */
   public Map<String, Object> getConvertedParameters(FacesContext facesContext, String viewId, Set<String> overridden)
   {
      Map<String, Object> parameters = new HashMap<String, Object>();
      for ( Page page: getPageStack(viewId) )
      {
         for ( Page.PageParameter pageParameter: page.getPageParameters() )
         {
            if ( !overridden.contains( pageParameter.getName() ) )
            {
               Object value = getPageParameterValue(facesContext, pageParameter);
               if (value!=null) 
               {
                  parameters.put( pageParameter.getName(), value );
               }
            }
         }
      }
      return parameters;
   }

   private Object getPageParameterValue(FacesContext facesContext, Page.PageParameter pageParameter)
   {
      ValueBinding valueBinding = pageParameter.getValueBinding();
      if (valueBinding==null)
      {
         return Contexts.getPageContext().get( pageParameter.getName() );
      }
      else
      {
         return getParameterValue(facesContext, pageParameter);
      }
   }

   private Object getParameterValue(FacesContext facesContext, Page.PageParameter pageParameter)
   {
      Object value = pageParameter.getValueBinding().getValue();
      if (value==null)
      {
         return null;
      }
      else
      {
         Converter converter = null;
         try
         {
            converter = pageParameter.getConverter();
         }
         catch (RuntimeException re)
         {
            //YUCK! due to bad JSF/MyFaces error handling
            return null;
         }
         
         return converter==null ? 
                  value : converter.getAsString( facesContext, facesContext.getViewRoot(), value );
      }
   }
   
   /**
    * Apply any page parameters passed as parameter values to the model.
    */
   public void applyRequestParameterValues(FacesContext facesContext)
   {
      String viewId = facesContext.getViewRoot().getViewId();
      Map<String, String[]> requestParameters = Parameters.getRequestParameters();
      for ( Page page: getPageStack(viewId) )
      {
         for ( Page.PageParameter pageParameter: page.getPageParameters() )
         {  
            
            String[] parameterValues = requestParameters.get(pageParameter.getName());
            if (parameterValues==null || parameterValues.length==0)
            {
               continue;
            }
            if (parameterValues.length>1)
            {
               throw new IllegalArgumentException("page parameter may not be multi-valued: " + pageParameter.getName());
            }         
            String stringValue = parameterValues[0];
   
            Converter converter;
            try
            {
               converter = pageParameter.getConverter();
            }
            catch (RuntimeException re)
            {
               //YUCK! due to bad JSF/MyFaces error handling
               continue;
            }
            
            Object value = converter==null ? 
                  stringValue :
                  converter.getAsObject( facesContext, facesContext.getViewRoot(), stringValue );

            ValueBinding valueBinding = pageParameter.getValueBinding();
            if (valueBinding==null)
            {
               Contexts.getPageContext().set( pageParameter.getName(), value );
            }
            else
            {
               valueBinding.setValue(value);
            }
         }
      }
   }

   /**
    * Apply any page parameters passed as view root attributes to the model.
    */
   public void applyViewRootValues(FacesContext facesContext)
   {
      String viewId = facesContext.getViewRoot().getViewId();
      for ( Page page: getPageStack(viewId) )
      {
         for ( Page.PageParameter pageParameter: page.getPageParameters() )
         {         
            ValueBinding valueBinding = pageParameter.getValueBinding();
            if (valueBinding!=null)
            {
               Object object = Contexts.getPageContext().get( pageParameter.getName() );
               if (object!=null)
               {
                  valueBinding.setValue(object);
               }
            }
         }
      }
   }

   /**
    * The global setting for no-conversation-viewid.
    * 
    * @return a JSF view id
    */
   public String getNoConversationViewId()
   {
      return noConversationViewId;
   }

   public void setNoConversationViewId(String noConversationViewId)
   {
      this.noConversationViewId = noConversationViewId;
   }
   
   /**
    * Encode page parameters into a URL
    * 
    * @param url the base URL
    * @param viewId the JSF view id of the page
    * @return the URL with parameters appended
    */
   public String encodePageParameters(FacesContext facesContext, String url, String viewId)
   {
      Map<String, Object> parameters = getConvertedParameters(facesContext, viewId);
      return Manager.instance().encodeParameters(url, parameters);
   }

   /**
    * Store the page parameters to the JSF view root
    */
   public void storePageParameters(FacesContext facesContext)
   {
      String viewId = facesContext.getViewRoot().getViewId();
      for ( Map.Entry<String, Object> param: getParameters(viewId).entrySet() )
      {
         Contexts.getPageContext().set( param.getKey(), param.getValue() );
      }
   }

   /**
    * Search for a defined no-conversation-view-id, beginning with
    * the most specific view id, then wildcarded view ids, and 
    * finally the global setting
    */
   public String getNoConversationViewId(String viewId)
   {
      List<Page> stack = getPageStack(viewId);
      for (int i=stack.size()-1; i>=0; i--)
      {
         Page page = stack.get(i);
         String noConversationViewId = page.getNoConversationViewId();
         if (noConversationViewId!=null)
         {
            return noConversationViewId;
         }
      }
      return this.noConversationViewId;
   }

   /**
    * Search for a defined conversation timeout, beginning with
    * the most specific view id, then wildcarded view ids, and 
    * finally the global setting from Manager
    */
   public Integer getTimeout(String viewId)
   {
      List<Page> stack = getPageStack(viewId);
      for (int i=stack.size()-1; i>=0; i--)
      {
         Page page = stack.get(i);
         Integer timeout = page.getTimeout();
         if (timeout!=null)
         {
            return timeout;
         }
      }
      return Manager.instance().getConversationTimeout();
   }

   public static String getSuffix()
   {
      String defaultSuffix = FacesContext.getCurrentInstance().getExternalContext()
            .getInitParameter(ViewHandler.DEFAULT_SUFFIX_PARAM_NAME);
      return defaultSuffix == null ? ViewHandler.DEFAULT_SUFFIX : defaultSuffix;
   
   }

}
