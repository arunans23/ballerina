/*
*  Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
*  WSO2 Inc. licenses this file to you under the Apache License,
*  Version 2.0 (the "License"); you may not use this file except
*  in compliance with the License.
*  You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
*  Unless required by applicable law or agreed to in writing,
*  software distributed under the License is distributed on an
*  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
*  KIND, either express or implied.  See the License for the
*  specific language governing permissions and limitations
*  under the License.
*/

package org.ballerinalang.net.uri.parser;

import org.ballerinalang.net.http.Constants;
import org.ballerinalang.net.http.HttpResource;
import org.ballerinalang.net.uri.DispatcherUtil;
import org.ballerinalang.util.exceptions.BallerinaException;
import org.wso2.carbon.transport.http.netty.message.HTTPCarbonMessage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Node represents different types of path segments in the uri-template.
 */
public abstract class Node {

    protected String token;
    protected List<HttpResource> resource;
    protected boolean isFirstTraverse = true;
    protected List<Node> childNodesList = new LinkedList<>();

    protected Node(String token) {
        this.token = token;
    }

    public Node addChild(Node childNode) {
        Node node = childNode;
        Node matchingChildNode = getMatchingChildNode(childNode, childNodesList);
        if (matchingChildNode != null) {
            node = matchingChildNode;
        } else {
            this.childNodesList.add(node);
        }

        Collections.sort(childNodesList, (o1, o2) -> getIntValue(o2) - getIntValue(o1));

        return node;
    }

    public HttpResource matchAll(String uriFragment, Map<String, String> variables, HTTPCarbonMessage carbonMessage,
                                 int start) {
        int matchLength = match(uriFragment, variables);
        if (matchLength < 0) {
            return null;
        }
        if (matchLength == uriFragment.length()) {
            return getResource(carbonMessage);
        }
        if (matchLength >= uriFragment.length()) {
            return null;
        }
        String subUriFragment = nextURIFragment(uriFragment, matchLength);
        String subPath = nextSubPath(subUriFragment);

        HttpResource resource;
        for (Node childNode : childNodesList) {
            if (childNode instanceof Literal) {
                String regex = childNode.getToken();
                if (regex.equals("*")) {
                    regex = "." + regex;
                    if (!subPath.matches(regex)) {
                        continue;
                    }
                    resource = childNode.matchAll(subUriFragment, variables,
                            carbonMessage, start + matchLength);
                    if (resource != null) {
                        return resource;
                    }
                    continue;
                }
                if (!subPath.contains(regex)) {
                    continue;
                }
                resource = childNode.matchAll(subUriFragment, variables, carbonMessage,
                        start + matchLength);
                if (resource != null) {
                    return resource;
                }
                continue;
            }
            resource = childNode.matchAll(subUriFragment, variables, carbonMessage,
                    start + matchLength);
            if (resource != null) {
                return resource;
            }
        }
        return null;
    }

    public HttpResource getResource(HTTPCarbonMessage carbonMessage) {
        if (this.resource == null) {
            return null;
        }
        HttpResource resource = validateHTTPMethod(this.resource, carbonMessage);
        if (resource == null) {
            return null;
        }
        validateConsumes(resource, carbonMessage);
        validateProduces(resource, carbonMessage);
        return resource;
    }

    private HttpResource validateHTTPMethod(List<HttpResource> resources, HTTPCarbonMessage carbonMessage) {
        HttpResource resource = null;
        boolean isOptionsRequest = false;
        String httpMethod = (String) carbonMessage.getProperty(Constants.HTTP_METHOD);
        for (HttpResource resourceInfo : resources) {
            if (DispatcherUtil.isMatchingMethodExist(resourceInfo, httpMethod)) {
                resource = resourceInfo;
                break;
            }
        }
        if (resource == null) {
            resource = tryMatchingToDefaultVerb(resources);
        }
        if (resource == null) {
            isOptionsRequest = setAllowHeadersIfOPTIONS(httpMethod, carbonMessage);
        }
        if (resource != null) {
            return resource;
        }
        if (!isOptionsRequest) {
            carbonMessage.setProperty(Constants.HTTP_STATUS_CODE, 405);
            throw new BallerinaException("Method not allowed");
        }
        return null;
    }

    public void setResource(HttpResource newResource) {
        if (isFirstTraverse) {
            this.resource = new ArrayList<>();
            this.resource.add(newResource);
            isFirstTraverse = false;
            return;
        }
        List<String> newMethods = newResource.getMethods();
        if (newMethods == null) {
            for (HttpResource previousResource : this.resource) {
                if (previousResource.getMethods() == null) {
                    //if both resources do not have methods but same URI, then throw following error.
                    throw new BallerinaException("Seems two resources have the same addressable URI, "
                            + previousResource.getName() + " and " + newResource.getName());
                }
            }
            this.resource.add(newResource);
            return;
        }
        this.resource.forEach(r -> {
            for (String newMethod : newMethods) {
                if (DispatcherUtil.isMatchingMethodExist(r, newMethod)) {
                    throw new BallerinaException("Seems two resources have the same addressable URI, "
                            + r.getName() + " and " + newResource.getName());
                }
            }
        });
        this.resource.add(newResource);
    }

    abstract String expand(Map<String, String> variables);

    abstract int match(String uriFragment, Map<String, String> variables);

    abstract String getToken();

    abstract char getFirstCharacter();

    private Node getMatchingChildNode(Node prospectiveChild, List<Node> existingChildren) {
        boolean isExpression = prospectiveChild instanceof Expression;
        String prospectiveChildToken = prospectiveChild.getToken();

        for (Node existingChild : existingChildren) {
            if (isExpression && existingChild instanceof Expression) {
                return existingChild;
            }
            if (existingChild.getToken().equals(prospectiveChildToken)) {
                return existingChild;
            }
        }

        return null;
    }

    private int getIntValue(Node node) {
        if (node instanceof Literal) {
            if (node.getToken().equals("*")) {
                return 0;
            }
            return node.getToken().length() + 5;
        } else if (node instanceof FragmentExpression) {
            return 4;
        } else if (node instanceof ReservedStringExpression) {
            return 3;
        } else if (node instanceof LabelExpression) {
            return 2;
        } else {
            return 1;
        }
    }

    private String nextURIFragment(String uri, int matchLength) {
        String uriFragment = uri;
        if (uriFragment.startsWith("/")) {
            uriFragment = uriFragment.substring(matchLength);
        } else if (uriFragment.contains("/")) {
            if (uriFragment.charAt(matchLength) == '/') {
                uriFragment = uriFragment.substring(matchLength + 1);
            } else {
                uriFragment = uriFragment.substring(matchLength);
            }
        } else {
            uriFragment = uriFragment.substring(matchLength);
        }
        return uriFragment;
    }

    private String nextSubPath(String uriFragment) {
        String subPath;
        if (uriFragment.contains("/")) {
            subPath = uriFragment.substring(0, uriFragment.indexOf("/"));
        } else {
            subPath = uriFragment;
        }
        return subPath;
    }

    private HttpResource tryMatchingToDefaultVerb(List<HttpResource> resources) {
        for (HttpResource resourceInfo : resources) {
            if (resourceInfo.getMethods() == null) {
                //this means, no method mentioned in the resource, hence it has all the methods by default.
                return resourceInfo;
            }
        }
        return null;
    }

    private boolean setAllowHeadersIfOPTIONS(String httpMethod, HTTPCarbonMessage cMsg) {
        if (httpMethod.equals(Constants.HTTP_METHOD_OPTIONS)) {
            cMsg.setHeader(Constants.ALLOW, getAllowHeaderValues(cMsg));
            return true;
        }
        return false;
    }

    private String getAllowHeaderValues(HTTPCarbonMessage cMsg) {
        List<String> methods = new ArrayList<>();
        List<HttpResource> resourceInfos = new ArrayList<>();
        for (HttpResource resourceInfo : this.resource) {
            if (resourceInfo.getMethods() != null) {
                methods.addAll(resourceInfo.getMethods());
            }
            resourceInfos.add(resourceInfo);
        }
        cMsg.setProperty(Constants.PREFLIGHT_RESOURCES, resourceInfos);
        methods = DispatcherUtil.validateAllowMethods(methods);
        return DispatcherUtil.concatValues(methods, false);
    }

    public HttpResource validateConsumes(HttpResource resource, HTTPCarbonMessage cMsg) {
        String contentMediaType = extractContentMediaType(cMsg.getHeader(Constants.CONTENT_TYPE_HEADER));
        List<String> consumesList = resource.getConsumes();

        if (consumesList == null) {
            return resource;
        }
        //when Content-Type header is not set, treat it as "application/octet-stream"
        contentMediaType = (contentMediaType != null ? contentMediaType : Constants.VALUE_ATTRIBUTE);
        for (String consumeType : consumesList) {
            if (contentMediaType.equals(consumeType.trim())) {
                return resource;
            }
        }
        cMsg.setProperty(Constants.HTTP_STATUS_CODE, 415);
        throw new BallerinaException();
    }

    private String extractContentMediaType(String header) {
        if (header == null) {
            return null;
        }
        if (header.contains(";")) {
            header = header.substring(0, header.indexOf(";")).trim();
        }
        return header;
    }

    public HttpResource validateProduces(HttpResource resource, HTTPCarbonMessage cMsg) {
        List<String> acceptMediaTypes = extractAcceptMediaTypes(cMsg.getHeader(Constants.ACCEPT_HEADER));
        List<String> producesList = resource.getProduces();

        if (producesList == null || acceptMediaTypes == null) {
            return resource;
        }
        //If Accept header field is not present, then it is assumed that the client accepts all media types.
        if (acceptMediaTypes.contains("*/*")) {
            return resource;
        }
        if (acceptMediaTypes.stream().anyMatch(mediaType -> mediaType.contains("/*"))) {
            List<String> subTypeWildCardMediaTypes = acceptMediaTypes.stream()
                    .filter(mediaType -> mediaType.contains("/*"))
                    .map(mediaType -> mediaType.substring(0, mediaType.indexOf("/")))
                    .collect(Collectors.toList());
            for (String token : resource.getProducesSubTypes()) {
                if (subTypeWildCardMediaTypes.contains(token)) {
                    return resource;
                }
            }
        }
        List<String> noWildCardMediaTypes = acceptMediaTypes.stream()
                .filter(mediaType -> !mediaType.contains("/*")).collect(Collectors.toList());
        for (String produceType : producesList) {
            if (noWildCardMediaTypes.contains(produceType)) {
                return resource;
            }
        }
        cMsg.setProperty(Constants.HTTP_STATUS_CODE, 406);
        throw new BallerinaException();
    }

    private List<String> extractAcceptMediaTypes(String header) {
        List<String> acceptMediaTypes = new ArrayList();
        if (header == null) {
            return null;
        }
        if (header.contains(",")) {
            //process headers like this: text/*;q=0.3, text/html;Level=1;q=0.7, */*
            acceptMediaTypes = Arrays.stream(header.split(","))
                    .map(mediaRange -> mediaRange.contains(";") ? mediaRange
                            .substring(0, mediaRange.indexOf(";")) : mediaRange)
                    .map(String::trim).distinct().collect(Collectors.toList());
        } else if (header.contains(";")) {
            //process headers like this: text/*;q=0.3
            acceptMediaTypes.add(header.substring(0, header.indexOf(";")).trim());
        } else {
            acceptMediaTypes.add(header.trim());
        }
        return acceptMediaTypes;
    }
}
