/*
 * Copyright (c) 2008-2018 Mozilla Foundation
 *
 * Permission is hereby granted, free of charge, to any person obtaining a
 * copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
 * THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */

package nu.validator.checker.schematronequiv;

import java.io.StringReader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;

import nu.validator.checker.Checker;
import nu.validator.checker.LocatorImpl;
import nu.validator.checker.TaintableLocatorImpl;
import nu.validator.client.TestRunner;
import nu.validator.messages.MessageEmitterAdapter;


import org.w3c.css.css.StyleSheetParser;
import org.w3c.css.parser.CssError;
import org.w3c.css.parser.CssParseException;
import org.w3c.css.parser.Errors;
import org.w3c.css.util.ApplContext;

import org.xml.sax.Attributes;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

public class MapmlAssertions extends Checker {

    private static boolean equalsIgnoreAsciiCase(String one, String other) {
        if (other == null) {
            return one == null;
        }
        if (one.length() != other.length()) {
            return false;
        }
        for (int i = 0; i < one.length(); i++) {
            char c0 = one.charAt(i);
            char c1 = other.charAt(i);
            if (c0 >= 'A' && c0 <= 'Z') {
                c0 += 0x20;
            }
            if (c1 >= 'A' && c1 <= 'Z') {
                c1 += 0x20;
            }
            if (c0 != c1) {
                return false;
            }
        }
        return true;
    }

    private static final String trimSpaces(String str) {
        return trimLeadingSpaces(trimTrailingSpaces(str));
    }

    private static final String trimLeadingSpaces(String str) {
        if (str == null) {
            return null;
        }
        for (int i = str.length(); i > 0; --i) {
            char c = str.charAt(str.length() - i);
            if (!(' ' == c || '\t' == c || '\n' == c || '\f' == c
                    || '\r' == c)) {
                return str.substring(str.length() - i, str.length());
            }
        }
        return "";
    }

    private static final String trimTrailingSpaces(String str) {
        if (str == null) {
            return null;
        }
        for (int i = str.length() - 1; i >= 0; --i) {
            char c = str.charAt(i);
            if (!(' ' == c || '\t' == c || '\n' == c || '\f' == c
                    || '\r' == c)) {
                return str.substring(0, i + 1);
            }
        }
        return "";
    }

    private static final Map<String, String[]> INPUT_ATTRIBUTES = new HashMap<>();

    static {
        INPUT_ATTRIBUTES.put("autocomplete",
                new String[] { "hidden", "text", "search", "url", "tel", "email",
                        "password", "date", "month", "week", "time",
                        "datetime-local", "number", "range", "color" });
        INPUT_ATTRIBUTES.put("list",
                new String[] { "text", "search", "url", "tel", "email",
                        "date", "month", "week", "time",
                        "datetime-local", "number", "range", "color" });
        INPUT_ATTRIBUTES.put("maxlength", new String[] { "text", "search",
                "url", "tel", "email", "password" });
        INPUT_ATTRIBUTES.put("minlength", new String[] { "text", "search",
                "url", "tel", "email", "password" });
        INPUT_ATTRIBUTES.put("pattern", new String[] { "text", "search", "url",
                "tel", "email", "password" });
        INPUT_ATTRIBUTES.put("placeholder", new String[] { "text", "search",
                "url", "tel", "email", "password", "number" });
        INPUT_ATTRIBUTES.put("readonly",
                new String[] { "text", "search", "url", "tel", "email",
                        "password", "date", "month", "week", "time",
                        "datetime-local", "number" });
        INPUT_ATTRIBUTES.put("required",
                new String[] { "text", "search", "url", "tel", "email",
                        "password", "date", "month", "week", "time",
                        "datetime-local", "number", "checkbox", "radio",
                        "file" });
        INPUT_ATTRIBUTES.put("size", new String[] { "text", "search", "url",
                "tel", "email", "password" });
    }

    private static final String[] INTERACTIVE_ELEMENTS = { "label", "select" };

    private static final String[] SPECIAL_ANCESTORS = { "head", "body", "extent" };

    private static int specialAncestorNumber(String name) {
        for (int i = 0; i < SPECIAL_ANCESTORS.length; i++) {
            if (name == SPECIAL_ANCESTORS[i]) {
                return i;
            }
        }
        return -1;
    }

    private static Map<String, Integer> ANCESTOR_MASK_BY_DESCENDANT = new HashMap<>();

    private static void registerProhibitedAncestor(String ancestor,
            String descendant) {
        int number = specialAncestorNumber(ancestor);
        if (number == -1) {
            throw new IllegalStateException(
                    "Ancestor not found in array: " + ancestor);
        }
        Integer maskAsObject = ANCESTOR_MASK_BY_DESCENDANT.get(descendant);
        int mask = 0;
        if (maskAsObject != null) {
            mask = maskAsObject.intValue();
        }
        mask |= (1 << number);
        ANCESTOR_MASK_BY_DESCENDANT.put(descendant, Integer.valueOf(mask));
    }

    static {
        registerProhibitedAncestor("extent", "extent");
    }
    private static final int HEAD_MASK = (1 << specialAncestorNumber("head"));

    private static final int BODY_MASK = (1 << specialAncestorNumber("body"));

    private static final int EXTENT_MASK = (1 << specialAncestorNumber("extent"));

    private static final Map<String, Set<String>> REQUIRED_ROLE_ANCESTOR_BY_DESCENDANT = new HashMap<>();

    private static final Map<String, Set<String>> ariaOwnsIdsByRole = new HashMap<>();

    private static void registerRequiredAncestorRole(String parent,
            String child) {
        Set<String> parents = REQUIRED_ROLE_ANCESTOR_BY_DESCENDANT.get(child);
        if (parents == null) {
            parents = new HashSet<>();
        }
        parents.add(parent);
        REQUIRED_ROLE_ANCESTOR_BY_DESCENDANT.put(child, parents);
    }

    static {
        registerRequiredAncestorRole("combobox", "option");
        registerRequiredAncestorRole("listbox", "option");
        registerRequiredAncestorRole("radiogroup", "option");
        registerRequiredAncestorRole("menu", "option");
        registerRequiredAncestorRole("menu", "menuitem");
        registerRequiredAncestorRole("menu", "menuitemcheckbox");
        registerRequiredAncestorRole("menu", "menuitemradio");
        registerRequiredAncestorRole("menubar", "menuitem");
        registerRequiredAncestorRole("menubar", "menuitemcheckbox");
        registerRequiredAncestorRole("menubar", "menuitemradio");
        registerRequiredAncestorRole("tablist", "tab");
        registerRequiredAncestorRole("tree", "treeitem");
        registerRequiredAncestorRole("tree", "option");
        registerRequiredAncestorRole("group", "treeitem");
        registerRequiredAncestorRole("group", "listitem");
        registerRequiredAncestorRole("group", "menuitemradio");
        registerRequiredAncestorRole("list", "listitem");
        registerRequiredAncestorRole("row", "cell");
        registerRequiredAncestorRole("row", "gridcell");
        registerRequiredAncestorRole("row", "columnheader");
        registerRequiredAncestorRole("row", "rowheader");
        registerRequiredAncestorRole("grid", "row");
        registerRequiredAncestorRole("grid", "rowgroup");
        registerRequiredAncestorRole("rowgroup", "row");
        registerRequiredAncestorRole("treegrid", "row");
        registerRequiredAncestorRole("treegrid", "rowgroup");
        registerRequiredAncestorRole("table", "rowgroup");
        registerRequiredAncestorRole("table", "row");
    }

    private static final Map<String, String> ELEMENTS_THAT_NEVER_NEED_ROLE = new HashMap<>();

    static {
        ELEMENTS_THAT_NEVER_NEED_ROLE.put("body", "document");
        ELEMENTS_THAT_NEVER_NEED_ROLE.put("datalist", "listbox");
        ELEMENTS_THAT_NEVER_NEED_ROLE.put("details", "group");
        ELEMENTS_THAT_NEVER_NEED_ROLE.put("form", "form");
        ELEMENTS_THAT_NEVER_NEED_ROLE.put("hr", "separator");
        ELEMENTS_THAT_NEVER_NEED_ROLE.put("main", "main");
        ELEMENTS_THAT_NEVER_NEED_ROLE.put("math", "math");
        ELEMENTS_THAT_NEVER_NEED_ROLE.put("meter", "progressbar");
        ELEMENTS_THAT_NEVER_NEED_ROLE.put("nav", "navigation");
        ELEMENTS_THAT_NEVER_NEED_ROLE.put("option", "option");
        ELEMENTS_THAT_NEVER_NEED_ROLE.put("progress", "progressbar");
        ELEMENTS_THAT_NEVER_NEED_ROLE.put("select", "listbox");
        ELEMENTS_THAT_NEVER_NEED_ROLE.put("summary", "button");
        ELEMENTS_THAT_NEVER_NEED_ROLE.put("textarea", "textbox");
    }

    private static final Map<String, String> INPUT_TYPES_WITH_IMPLICIT_ROLE = new HashMap<>();

    static {
        INPUT_TYPES_WITH_IMPLICIT_ROLE.put("button", "button");
        INPUT_TYPES_WITH_IMPLICIT_ROLE.put("checkbox", "checkbox");
        INPUT_TYPES_WITH_IMPLICIT_ROLE.put("image", "button");
        INPUT_TYPES_WITH_IMPLICIT_ROLE.put("number", "spinbutton");
        INPUT_TYPES_WITH_IMPLICIT_ROLE.put("radio", "radio");
        INPUT_TYPES_WITH_IMPLICIT_ROLE.put("range", "slider");
        INPUT_TYPES_WITH_IMPLICIT_ROLE.put("reset", "button");
        INPUT_TYPES_WITH_IMPLICIT_ROLE.put("submit", "button");
    }

    private static final Set<String> ATTRIBUTES_WITH_IMPLICIT_STATE_OR_PROPERTY = new HashSet<>();

    static {
        ATTRIBUTES_WITH_IMPLICIT_STATE_OR_PROPERTY.add("disabled");
        ATTRIBUTES_WITH_IMPLICIT_STATE_OR_PROPERTY.add("hidden");
        ATTRIBUTES_WITH_IMPLICIT_STATE_OR_PROPERTY.add("readonly");
        ATTRIBUTES_WITH_IMPLICIT_STATE_OR_PROPERTY.add("required");
    }

    private class IdrefLocator {
        private final Locator locator;

        private final String idref;

        private final String additional;

        /**
         * @param locator
         * @param idref
         */
        public IdrefLocator(Locator locator, String idref) {
            this.locator = new LocatorImpl(locator);
            this.idref = idref;
            this.additional = null;
        }

        public IdrefLocator(Locator locator, String idref, String additional) {
            this.locator = new LocatorImpl(locator);
            this.idref = idref;
            this.additional = additional;
        }

        /**
         * Returns the locator.
         *
         * @return the locator
         */
        public Locator getLocator() {
            return locator;
        }

        /**
         * Returns the idref.
         *
         * @return the idref
         */
        public String getIdref() {
            return idref;
        }

        /**
         * Returns the additional.
         *
         * @return the additional
         */
        public String getAdditional() {
            return additional;
        }
    }

    private class StackNode {
        private final int ancestorMask;

        private final String name; // null if not HTML

        private final StringBuilder textContent;

        private final String role;

        private final String activeDescendant;

        private final String forAttr;

        private Set<Locator> imagesLackingAlt = new HashSet<>();

        private Locator nonEmptyOption = null;

        private Locator locator = null;

        private boolean selectedOptions = false;

        private boolean labeledDescendants = false;

        private boolean trackDescendants = false;

        private boolean textNodeFound = false;

        private boolean imgFound = false;

        private boolean embeddedContentFound = false;

        private boolean figcaptionNeeded = false;

        private boolean figcaptionContentFound = false;

        private boolean headingFound = false;

        private boolean optionNeeded = false;

        private boolean optionFound = false;

        private boolean noValueOptionFound = false;

        private boolean emptyValueOptionFound = false;

        private boolean isCollectingCharacters = false;

        /**
         * @param ancestorMask
         */
        public StackNode(int ancestorMask, String name, String role,
                String activeDescendant, String forAttr) {
            this.ancestorMask = ancestorMask;
            this.name = name;
            this.role = role;
            this.activeDescendant = activeDescendant;
            this.forAttr = forAttr;
            this.textContent = new StringBuilder();
        }

        /**
         * Returns the ancestorMask.
         *
         * @return the ancestorMask
         */
        public int getAncestorMask() {
            return ancestorMask;
        }

        /**
         * Returns the name.
         *
         * @return the name
         */
        public String getName() {
            return name;
        }

        /**
         * Returns the selectedOptions.
         *
         * @return the selectedOptions
         */
        public boolean isSelectedOptions() {
            return selectedOptions;
        }

        /**
         * Sets the selectedOptions.
         *
         * @param selectedOptions
         *            the selectedOptions to set
         */
        public void setSelectedOptions() {
            this.selectedOptions = true;
        }

        /**
         * Returns the labeledDescendants.
         *
         * @return the labeledDescendants
         */
        public boolean isLabeledDescendants() {
            return labeledDescendants;
        }

        /**
         * Sets the labeledDescendants.
         *
         * @param labeledDescendants
         *            the labeledDescendants to set
         */
        public void setLabeledDescendants() {
            this.labeledDescendants = true;
        }

        /**
         * Returns the trackDescendants.
         *
         * @return the trackDescendants
         */
        public boolean isTrackDescendant() {
            return trackDescendants;
        }

        /**
         * Sets the trackDescendants.
         *
         * @param trackDescendants
         *            the trackDescendants to set
         */
        public void setTrackDescendants() {
            this.trackDescendants = true;
        }

        /**
         * Returns the role.
         *
         * @return the role
         */
        public String getRole() {
            return role;
        }

        /**
         * Returns the activeDescendant.
         *
         * @return the activeDescendant
         */
        public String getActiveDescendant() {
            return activeDescendant;
        }

        /**
         * Returns the forAttr.
         *
         * @return the forAttr
         */
        public String getForAttr() {
            return forAttr;
        }

        /**
         * Returns the textNodeFound.
         *
         * @return the textNodeFound
         */
        public boolean hasTextNode() {
            return textNodeFound;
        }

        /**
         * Sets the textNodeFound.
         */
        public void setTextNodeFound() {
            this.textNodeFound = true;
        }

        /**
         * Returns the imgFound.
         *
         * @return the imgFound
         */
        public boolean hasImg() {
            return imgFound;
        }

        /**
         * Sets the imgFound.
         */
        public void setImgFound() {
            this.imgFound = true;
        }

        /**
         * Returns the embeddedContentFound.
         *
         * @return the embeddedContentFound
         */
        public boolean hasEmbeddedContent() {
            return embeddedContentFound;
        }

        /**
         * Sets the embeddedContentFound.
         */
        public void setEmbeddedContentFound() {
            this.embeddedContentFound = true;
        }

        /**
         * Returns the figcaptionNeeded.
         *
         * @return the figcaptionNeeded
         */
        public boolean needsFigcaption() {
            return figcaptionNeeded;
        }

        /**
         * Sets the figcaptionNeeded.
         */
        public void setFigcaptionNeeded() {
            this.figcaptionNeeded = true;
        }

        /**
         * Returns the figcaptionContentFound.
         *
         * @return the figcaptionContentFound
         */
        public boolean hasFigcaptionContent() {
            return figcaptionContentFound;
        }

        /**
         * Sets the figcaptionContentFound.
         */
        public void setFigcaptionContentFound() {
            this.figcaptionContentFound = true;
        }

        /**
         * Returns the headingFound.
         *
         * @return the headingFound
         */
        public boolean hasHeading() {
            return headingFound;
        }

        /**
         * Sets the headingFound.
         */
        public void setHeadingFound() {
            this.headingFound = true;
        }

        /**
         * Returns the imagesLackingAlt
         *
         * @return the imagesLackingAlt
         */
        public Set<Locator> getImagesLackingAlt() {
            return imagesLackingAlt;
        }

        /**
         * Adds to the imagesLackingAlt
         */
        public void addImageLackingAlt(Locator locator) {
            this.imagesLackingAlt.add(locator);
        }

        /**
         * Returns the optionNeeded.
         *
         * @return the optionNeeded
         */
        public boolean isOptionNeeded() {
            return optionNeeded;
        }

        /**
         * Sets the optionNeeded.
         */
        public void setOptionNeeded() {
            this.optionNeeded = true;
        }

        /**
         * Returns the optionFound.
         *
         * @return the optionFound
         */
        public boolean hasOption() {
            return optionFound;
        }

        /**
         * Sets the optionFound.
         */
        public void setOptionFound() {
            this.optionFound = true;
        }

        /**
         * Returns the noValueOptionFound.
         *
         * @return the noValueOptionFound
         */
        public boolean hasNoValueOption() {
            return noValueOptionFound;
        }

        /**
         * Sets the noValueOptionFound.
         */
        public void setNoValueOptionFound() {
            this.noValueOptionFound = true;
        }

        /**
         * Returns the emptyValueOptionFound.
         *
         * @return the emptyValueOptionFound
         */
        public boolean hasEmptyValueOption() {
            return emptyValueOptionFound;
        }

        /**
         * Sets the emptyValueOptionFound.
         */
        public void setEmptyValueOptionFound() {
            this.emptyValueOptionFound = true;
        }

        /**
         * Returns the nonEmptyOption.
         *
         * @return the nonEmptyOption
         */
        public Locator nonEmptyOptionLocator() {
            return nonEmptyOption;
        }

        /**
         * Sets the nonEmptyOption.
         */
        public void setNonEmptyOption(Locator locator) {
            this.nonEmptyOption = locator;
        }

        /**
         * Sets the collectingCharacters.
         */
        public void setIsCollectingCharacters(boolean isCollectingCharacters) {
            this.isCollectingCharacters = isCollectingCharacters;
        }

        /**
         * Gets the collectingCharacters.
         */
        public boolean getIsCollectingCharacters() {
            return this.isCollectingCharacters;
        }

        /**
         * Appends to the textContent.
         */
        public void appendToTextContent(char ch[], int start, int length) {
            this.textContent.append(ch, start, length);
        }

        /**
         * Gets the textContent.
         */
        public StringBuilder getTextContent() {
            return this.textContent;
        }

        /**
         * Returns the locator.
         *
         * @return the locator
         */
        public Locator locator() {
            return locator;
        }

        /**
         * Sets the locator.
         */
        public void setLocator(Locator locator) {
            this.locator = locator;
        }

    }

    private StackNode[] stack;

    private int currentPtr;

    public MapmlAssertions() {
        super();
    }

    private HttpServletRequest request;

    private boolean sourceIsCss;

    public void setSourceIsCss(boolean sourceIsCss) {
        this.sourceIsCss = sourceIsCss;
    }

    private void incrementUseCounter(String useCounterName) {
        if (request != null) {
            request.setAttribute(
                    "http://validator.nu/properties/" + useCounterName, true);
        }
    }

    private void push(StackNode node) {
        currentPtr++;
        if (currentPtr == stack.length) {
            StackNode[] newStack = new StackNode[stack.length + 64];
            System.arraycopy(stack, 0, newStack, 0, stack.length);
            stack = newStack;
        }
        stack[currentPtr] = node;
    }

    private StackNode pop() {
        return stack[currentPtr--];
    }

    private StackNode peek() {
        return stack[currentPtr];
    }

    private Map<StackNode, Locator> openSingleSelects = new HashMap<>();

    private Map<StackNode, Locator> openLabels = new HashMap<>();

    private Map<StackNode, TaintableLocatorImpl> openMediaElements = new HashMap<>();

    private Map<StackNode, Locator> openActiveDescendants = new HashMap<>();

    private LinkedHashSet<IdrefLocator> formControlReferences = new LinkedHashSet<>();

    private LinkedHashSet<IdrefLocator> formElementReferences = new LinkedHashSet<>();

    private LinkedHashSet<IdrefLocator> needsAriaOwner = new LinkedHashSet<>();

    private Set<String> formControlIds = new HashSet<>();

    private Set<String> formElementIds = new HashSet<>();

    private LinkedHashSet<IdrefLocator> listReferences = new LinkedHashSet<>();

    private Set<String> listIds = new HashSet<>();

    private LinkedHashSet<IdrefLocator> ariaReferences = new LinkedHashSet<>();

    private Set<String> allIds = new HashSet<>();

    private int currentFigurePtr;

    private int currentHeadingPtr;

    private int currentSectioningElementPtr;

    private boolean hasVisibleMain;

    private boolean hasMetaCharset;

    private boolean hasMetaDescription;

    private boolean hasContentTypePragma;

    private boolean hasAutofocus;

    private boolean hasTopLevelH1;

    private int numberOfTemplatesDeep = 0;

    private Set<Locator> secondLevelH1s = new HashSet<>();

    private Map<Locator, Map<String, String>> siblingSources = new ConcurrentHashMap<>();

// keep for now
//    private final void errObsoleteAttribute(String attribute, String element,
//            String suggestion) throws SAXException {
//        err("The \u201C" + attribute + "\u201D attribute on the \u201C"
//                + element + "\u201D element is obsolete." + suggestion);
//    }
//
//    private final void warnObsoleteAttribute(String attribute, String element,
//            String suggestion) throws SAXException {
//        warn("The \u201C" + attribute + "\u201D attribute on the \u201C"
//                + element + "\u201D element is obsolete." + suggestion);
//    }

    /**
     * @see nu.validator.checker.Checker#endDocument()
     */
    @Override
    public void endDocument() throws SAXException {
        // label for
        for (IdrefLocator idrefLocator : formControlReferences) {
            if (!formControlIds.contains(idrefLocator.getIdref())) {
                err("The value of the \u201Cfor\u201D attribute of the"
                        + " \u201Clabel\u201D element must be the ID of a"
                        + " non-hidden form control.",
                        idrefLocator.getLocator());
            }
        }

        // references to IDs from form attributes
        for (IdrefLocator idrefLocator : formElementReferences) {
            if (!formElementIds.contains(idrefLocator.getIdref())) {
                err("The \u201Cform\u201D attribute must refer to a form element.",
                        idrefLocator.getLocator());
            }
        }

        // input list
        for (IdrefLocator idrefLocator : listReferences) {
            if (!listIds.contains(idrefLocator.getIdref())) {
                err("The \u201Clist\u201D attribute of the \u201Cinput\u201D element must refer to a \u201Cdatalist\u201D element.",
                        idrefLocator.getLocator());
            }
        }

        reset();
        stack = null;
    }

    private static double getDoubleAttribute(Attributes atts, String name) {
        String str = atts.getValue("", name);
        if (str == null) {
            return Double.NaN;
        } else {
            try {
                return Double.parseDouble(str);
            } catch (NumberFormatException e) {
                return Double.NaN;
            }
        }
    }

    /**
     * @see nu.validator.checker.Checker#endElement(java.lang.String,
     *      java.lang.String, java.lang.String)
     */
    @Override
    public void endElement(String uri, String localName, String name)
            throws SAXException {
        StackNode node = pop();
        String systemId = node.locator().getSystemId();
        String publicId = node.locator().getPublicId();
        Locator locator = null;
//        openSingleSelects.remove(node);
//        openLabels.remove(node);
//        openMediaElements.remove(node);
        if ("http://www.w3.org/1999/xhtml" == uri) {
            if ("select" == localName && node.isOptionNeeded()) {
                if (!node.hasOption()) {
                    err("A \u201Cselect\u201D element with a"
                            + " \u201Crequired\u201D attribute, and without a"
                            + " \u201Cmultiple\u201D attribute, and without a"
                            + " \u201Csize\u201D attribute whose value is"
                            + " greater than"
                            + " \u201C1\u201D, must have a child"
                            + " \u201Coption\u201D element.");
                }
                if (node.nonEmptyOptionLocator() != null) {
                    err("The first child \u201Coption\u201D element of a"
                            + " \u201Cselect\u201D element with a"
                            + " \u201Crequired\u201D attribute, and without a"
                            + " \u201Cmultiple\u201D attribute, and without a"
                            + " \u201Csize\u201D attribute whose value is"
                            + " greater than"
                            + " \u201C1\u201D, must have either an empty"
                            + " \u201Cvalue\u201D attribute, or must have no"
                            + " text content."
                            + " Consider either adding a placeholder option"
                            + " label, or adding a"
                            + " \u201Csize\u201D attribute with a value equal"
                            + " to the number of"
                            + " \u201Coption\u201D elements.",
                            node.nonEmptyOptionLocator());
                }
            } else if ("option" == localName
                    && !stack[currentPtr].hasOption()) {
                stack[currentPtr].setOptionFound();
            } else if ("style" == localName) {
                String styleContents = node.getTextContent().toString();
                int lineOffset = 0;
                if (styleContents.startsWith("\n")) {
                    lineOffset = 1;
                }
                ApplContext ac = new ApplContext("en");
                ac.setCssVersionAndProfile("css3svg");
                ac.setMedium("all");
                ac.setSuggestPropertyName(false);
                ac.setTreatVendorExtensionsAsWarnings(true);
                ac.setTreatCssHacksAsWarnings(true);
                ac.setWarningLevel(-1);
                ac.setFakeURL("file://localhost/StyleElement");
                StyleSheetParser styleSheetParser = new StyleSheetParser();
                styleSheetParser.parseStyleSheet(ac,
                        new StringReader(styleContents.substring(lineOffset)),
                        null);
                styleSheetParser.getStyleSheet().findConflicts(ac);
                Errors errors = styleSheetParser.getStyleSheet().getErrors();
                if (errors.getErrorCount() > 0) {
                    incrementUseCounter("style-element-errors-found");
                }
                for (int i = 0; i < errors.getErrorCount(); i++) {
                    String message = "";
                    String cssProperty = "";
                    String cssMessage = "";
                    CssError error = errors.getErrorAt(i);
                    int beginLine = error.getBeginLine() + lineOffset;
                    int beginColumn = error.getBeginColumn();
                    int endLine = error.getEndLine() + lineOffset;
                    int endColumn = error.getEndColumn();
                    if (beginLine == 0) {
                        continue;
                    }
                    Throwable ex = error.getException();
                    if (ex instanceof CssParseException) {
                        CssParseException cpe = (CssParseException) ex;
                        if ("generator.unrecognize" //
                                .equals(cpe.getErrorType())) {
                            cssMessage = "Parse Error";
                        }
                        if (cpe.getProperty() != null) {
                            cssProperty = String.format("\u201c%s\u201D: ",
                                    cpe.getProperty());
                        }
                        if (cpe.getMessage() != null) {
                            cssMessage = cpe.getMessage();
                        }
                        if (!"".equals(cssMessage)) {
                            message = cssProperty + cssMessage + ".";
                        }
                    } else {
                        message = ex.getMessage();
                    }
                    if (!"".equals(message)) {
                        int lastLine = node.locator.getLineNumber() //
                                + endLine - 1;
                        int lastColumn = endColumn;
                        int columnOffset = node.locator.getColumnNumber();
                        if (error.getBeginLine() == 1) {
                            if (lineOffset != 0) {
                                columnOffset = 0;
                            }
                        } else {
                            columnOffset = 0;
                        }
                        String prefix = sourceIsCss ? "" : "CSS: ";
                        SAXParseException spe = new SAXParseException( //
                                prefix + message, publicId, systemId, //
                                lastLine, lastColumn);
                        int[] start = {
                                node.locator.getLineNumber() + beginLine - 1,
                                beginColumn, columnOffset };
                        if ((getErrorHandler() instanceof MessageEmitterAdapter)
                                && !(getErrorHandler() instanceof TestRunner)) {
                            ((MessageEmitterAdapter) getErrorHandler()) //
                                    .errorWithStart(spe, start);
                        } else {
                            getErrorHandler().error(spe);
                        }
                    }
                }
            }
        }
        if ((locator = openActiveDescendants.remove(node)) != null) {
            warn("Attribute \u201Caria-activedescendant\u201D value should "
                    + "either refer to a descendant element, or should "
                    + "be accompanied by attribute \u201Caria-owns\u201D.",
                    locator);
        }
    }

    /**
     * @see nu.validator.checker.Checker#startDocument()
     */
    @Override
    public void startDocument() throws SAXException {
        reset();
        request = getRequest();
        stack = new StackNode[32];
        stack[0] = null;
        hasVisibleMain = false;
        hasMetaCharset = false;
        hasMetaDescription = false;
        hasContentTypePragma = false;
    }

    @Override
    public void reset() {
    }

    /**
     * @see nu.validator.checker.Checker#startElement(java.lang.String,
     *      java.lang.String, java.lang.String, org.xml.sax.Attributes)
     */
    @Override
    public void startElement(String uri, String localName, String name,
            Attributes atts) throws SAXException {
        Set<String> ids = new HashSet<>();
        String role = null;
        String inputTypeVal = null;
        String activeDescendant = null;
        String forAttr = null;
        boolean href = false;

        MapmlAssertions.StackNode parent = peek();
        int ancestorMask = 0;
        String parentName = null;
        if (parent != null) {
            ancestorMask = parent.getAncestorMask();
            parentName = parent.getName();
        }
        if ("http://www.w3.org/1999/xhtml" == uri) {
            boolean controls = false;
            boolean hidden = false;
            boolean toolbar = false;
            boolean selected = false;
            boolean itemid = false;
            boolean itemref = false;
            boolean itemscope = false;
            boolean itemtype = false;
            boolean tabindex = false;
            String lang = null;
            String id = null;
            String list = null;

            // Exclusions
            Integer maskAsObject;
            int mask = 0;
            String descendantUiString = "The element \u201C" + localName
                    + "\u201D";
            if ((maskAsObject = ANCESTOR_MASK_BY_DESCENDANT.get(
                    localName)) != null) {
                mask = maskAsObject.intValue();
            }
            if (mask != 0) {
                int maskHit = ancestorMask & mask;
                if (maskHit != 0) {
                    for (String ancestor : SPECIAL_ANCESTORS) {
                        if ((maskHit & 1) != 0) {
                            err(descendantUiString + " must not appear as a"
                                    + " descendant of the \u201C" + ancestor
                                    + "\u201D element.");
                        }
                        maskHit >>= 1;
                    }
                }
            }
            // Ancestor requirements/restrictions
            if ("input" == localName && ((ancestorMask & EXTENT_MASK) == 0)) {
                err("The \u201Cinput\u201D element must have a \u201Cextent\u201D ancestor.");
            }            
            // TODO refactor to validate input attributes (primarily) 
//            int len = atts.getLength();
//            for (int i = 0; i < len; i++) {
//                String attUri = atts.getURI(i);
//                if (attUri.length() == 0) {
//                    String attLocal = atts.getLocalName(i);
//                    if ("style" == attLocal) {
//                        String styleContents = atts.getValue(i);
//                        ApplContext ac = new ApplContext("en");
//                        ac.setCssVersionAndProfile("css3svg");
//                        ac.setMedium("all");
//                        ac.setSuggestPropertyName(false);
//                        ac.setTreatVendorExtensionsAsWarnings(true);
//                        ac.setTreatCssHacksAsWarnings(true);
//                        ac.setWarningLevel(-1);
//                        ac.setFakeURL("file://localhost/StyleAttribute");
//                        StyleSheetParser styleSheetParser = //
//                                new StyleSheetParser();
//                        styleSheetParser.parseStyleAttribute(ac,
//                                new ByteArrayInputStream(
//                                        styleContents.getBytes()),
//                                "", ac.getFakeURL(),
//                                getDocumentLocator().getLineNumber());
//                        styleSheetParser.getStyleSheet().findConflicts(ac);
//                        Errors errors = //
//                                styleSheetParser.getStyleSheet().getErrors();
//                        if (errors.getErrorCount() > 0) {
//                            incrementUseCounter("style-attribute-errors-found");
//                        }
//                        for (int j = 0; j < errors.getErrorCount(); j++) {
//                            String message = "";
//                            String cssProperty = "";
//                            String cssMessage = "";
//                            CssError error = errors.getErrorAt(j);
//                            Throwable ex = error.getException();
//                            if (ex instanceof CssParseException) {
//                                CssParseException cpe = (CssParseException) ex;
//                                if ("generator.unrecognize" //
//                                        .equals(cpe.getErrorType())) {
//                                    cssMessage = "Parse Error";
//                                }
//                                if (cpe.getProperty() != null) {
//                                    cssProperty = String.format(
//                                            "\u201c%s\u201D: ",
//                                            cpe.getProperty());
//                                }
//                                if (cpe.getMessage() != null) {
//                                    cssMessage = cpe.getMessage();
//                                }
//                                if (!"".equals(cssMessage)) {
//                                    message = cssProperty + cssMessage + ".";
//                                }
//                            } else {
//                                message = ex.getMessage();
//                            }
//                            if (!"".equals(message)) {
//                                err("CSS: " + message);
//                            }
//                        }
//                    } else if ("tabindex" == attLocal) {
//                        tabindex = true;
//                    } else if ("href" == attLocal) {
//                        href = true;
//                    } else if ("controls" == attLocal) {
//                        controls = true;
//                    } else if ("type" == attLocal && "param" != localName
//                            && "ol" != localName && "ul" != localName
//                            && "li" != localName) {
//                        if ("input" == localName) {
//                            inputTypeVal = atts.getValue(i).toLowerCase();
//                        }
//                        String attValue = atts.getValue(i);
//                        if (AttributeUtil.lowerCaseLiteralEqualsIgnoreAsciiCaseString(
//                                "hidden", attValue)) {
//                            hidden = true;
//                        } else if (AttributeUtil.lowerCaseLiteralEqualsIgnoreAsciiCaseString(
//                                "toolbar", attValue)) {
//                            toolbar = true;
//                        }
//
//                    } else if ("list" == attLocal) {
//                        list = atts.getValue(i);
//                    } else if ("lang" == attLocal) {
//                        lang = atts.getValue(i);
//                    } else if ("id" == attLocal) {
//                        id = atts.getValue(i);
//                    } else if ("for" == attLocal && "label" == localName) {
//                        forAttr = atts.getValue(i);
////                        ancestorMask |= LABEL_FOR_MASK;
//                    } else if ("selected" == attLocal) {
//                        selected = true;
//                    } else if ("itemid" == attLocal) {
//                        itemid = true;
//                    } else if ("itemref" == attLocal) {
//                        itemref = true;
//                    } else if ("itemscope" == attLocal) {
//                        itemscope = true;
//                    } else if ("itemtype" == attLocal) {
//                        itemtype = true;
//                    } else if (INPUT_ATTRIBUTES.containsKey(attLocal)
//                            && "input" == localName) {
//                        String[] allowedTypes = INPUT_ATTRIBUTES.get(attLocal);
//                        Arrays.sort(allowedTypes);
//                        inputTypeVal = inputTypeVal == null ? "text"
//                                : inputTypeVal;
//                        if (Arrays.binarySearch(allowedTypes,
//                                inputTypeVal) < 0) {
//                            err("Attribute \u201c" + attLocal
//                                    + "\u201d is only allowed when the input"
//                                    + " type is " + renderTypeList(allowedTypes)
//                                    + ".");
//                        }
//                    } else if (ATTRIBUTES_WITH_IMPLICIT_STATE_OR_PROPERTY.contains(
//                            attLocal)) {
//                        String stateOrProperty = "aria-" + attLocal;
//                        if (atts.getIndex("", stateOrProperty) > -1
//                                && "true".equals(
//                                        atts.getValue("", stateOrProperty))) {
//                            warn("Attribute \u201C" + stateOrProperty
//                                    + "\u201D is unnecessary for elements that"
//                                    + " have attribute \u201C" + attLocal
//                                    + "\u201D.");
//                        }
//                    }
//                }
//
//                if (atts.getType(i) == "ID" || "id" == atts.getLocalName(i)) {
//                    String attVal = atts.getValue(i);
//                    if (attVal.length() != 0) {
//                        ids.add(attVal);
//                    }
//                }
//            }
//            // TODO refactor link element assertions to suit MapML context
//            if ("link" == localName) {
//                boolean hasRel = false;
//                List<String> relList = new ArrayList<>();
//                if (atts.getIndex("", "rel") > -1) {
//                    hasRel = true;
//                    Collections.addAll(relList, //
//                            atts.getValue("", "rel") //
//                            .toLowerCase().split("\\s+"));
//                }
//                if (atts.getIndex("", "integrity") > -1
//                        && ((relList != null && !relList.contains("stylesheet")
//                                || !hasRel))) {
//                    err("A \u201Clink\u201D element with an"
//                            + " \u201Cintegrity\u201D attribute must have a"
//                            + " \u201Crel\u201D attribute that contains the"
//                            + " value \u201Cstylesheet\u201D.");
//                }
//                if (atts.getIndex("", "sizes") > -1
//                        && ((relList != null && !relList.contains("icon")
//                                && !relList.contains("apple-touch-icon"))
//                                && !relList.contains("apple-touch-icon-precomposed")
//                                || !hasRel)) {
//                    err("A \u201Clink\u201D element with a"
//                            + " \u201Csizes\u201D attribute must have a"
//                            + " \u201Crel\u201D attribute that contains the"
//                            + " value \u201Cicon\u201D or the value"
//                            + " \u201Capple-touch-icon\u201D or the value"
//                            + " \u201Capple-touch-icon-precomposed\u201D.");
//                }
//                if (atts.getIndex("", "color") > -1 //
//                        && (!hasRel || (relList != null
//                                && !relList.contains("mask-icon")))) {
//                    err("A \u201Clink\u201D element with a"
//                            + " \u201Ccolor\u201D attribute must have a"
//                            + " \u201Crel\u201D attribute that contains"
//                            + " the value \u201Cmask-icon\u201D.");
//                }
//                if (atts.getIndex("", "scope") > -1 //
//                        && ((relList != null
//                                && !relList.contains("serviceworker"))
//                                || !hasRel)) {
//                    err("A \u201Clink\u201D element with a"
//                            + " \u201Cscope\u201D attribute must have a"
//                            + " \u201Crel\u201D attribute that contains the"
//                            + " value \u201Cserviceworker\u201D.");
//                }
//                if (atts.getIndex("", "updateviacache") > -1 //
//                        && ((relList != null
//                                && !relList.contains("serviceworker"))
//                                || !hasRel)) {
//                    err("A \u201Clink\u201D element with an"
//                            + " \u201Cupdateviacache\u201D attribute must have a"
//                            + " \u201Crel\u201D attribute that contains the"
//                            + " value \u201Cserviceworker\u201D.");
//                }
//                if (atts.getIndex("", "workertype") > -1 //
//                        && ((relList != null
//                                && !relList.contains("serviceworker"))
//                                || !hasRel)) {
//                    err("A \u201Clink\u201D element with a"
//                            + " \u201Cworkertype\u201D attribute must have a"
//                            + " \u201Crel\u201D attribute that contains the"
//                            + " value \u201Cserviceworker\u201D.");
//                }
//                if ((ancestorMask & BODY_MASK) != 0
//                        && (relList != null
//                                && !(relList.contains("dns-prefetch")
//                                        || relList.contains("modulepreload")
//                                        || relList.contains("pingback")
//                                        || relList.contains("preconnect")
//                                        || relList.contains("prefetch")
//                                        || relList.contains("preload")
//                                        || relList.contains("prerender")
//                                        || relList.contains("stylesheet")))
//                        && atts.getIndex("", "itemprop") < 0
//                        && atts.getIndex("", "property") < 0) {
//                    err("A \u201Clink\u201D element must not appear"
//                            + " as a descendant of a \u201Cbody\u201D element"
//                            + " unless the \u201Clink\u201D element has an"
//                            + " \u201Citemprop\u201D attribute or has a"
//                            + " \u201Crel\u201D attribute whose value contains"
//                            + " \u201Cdns-prefetch\u201D,"
//                            + " \u201Cmodulepreload\u201D,"
//                            + " \u201Cpingback\u201D,"
//                            + " \u201Cpreconnect\u201D,"
//                            + " \u201Cprefetch\u201D,"
//                            + " \u201Cpreload\u201D,"
//                            + " \u201Cprerender\u201D, or"
//                            + " \u201Cstylesheet\u201D.");
//                }
//            }
        }
        if ("http://www.w3.org/1999/xhtml" == uri) {
            int number = specialAncestorNumber(localName);
            if (number > -1) {
                ancestorMask |= (1 << number);
            }
            MapmlAssertions.StackNode child = new MapmlAssertions.StackNode(ancestorMask, localName, role,
                    activeDescendant, forAttr);
            push(child);
        }
        stack[currentPtr].setLocator(new LocatorImpl(getDocumentLocator()));
    }

    /**
     * @see nu.validator.checker.Checker#characters(char[], int, int)
     */
    @Override
    public void characters(char[] ch, int start, int length)
            throws SAXException {
    }
    private CharSequence renderTypeList(String[] types) {
        StringBuilder sb = new StringBuilder();
        int len = types.length;
        for (int i = 0; i < len; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            if (i == len - 1) {
                sb.append("or ");
            }
            sb.append("\u201C");
            sb.append(types[i]);
            sb.append('\u201D');
        }
        return sb;
    }
}
