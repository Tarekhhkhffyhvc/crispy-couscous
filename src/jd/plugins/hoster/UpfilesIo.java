//jDownloader - Downloadmanager
//Copyright (C) 2009  JD-Team support@jdownloader.org
//
//This program is free software: you can redistribute it and/or modify
//it under the terms of the GNU General Public License as published by
//the Free Software Foundation, either version 3 of the License, or
//(at your option) any later version.
//
//This program is distributed in the hope that it will be useful,
//but WITHOUT ANY WARRANTY; without even the implied warranty of
//MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//GNU General Public License for more details.
//
//You should have received a copy of the GNU General Public License
//along with this program.  If not, see <http://www.gnu.org/licenses/>.
package jd.plugins.hoster;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.appwork.utils.Regex;
import org.appwork.utils.parser.UrlQuery;
import org.jdownloader.captcha.v2.challenge.hcaptcha.CaptchaHelperHostPluginHCaptcha;

import jd.PluginWrapper;
import jd.http.Browser;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.plugins.components.PluginJSonUtils;

@HostPlugin(revision = "$Revision$", interfaceVersion = 2, names = {}, urls = {})
public class UpfilesIo extends PluginForHost {
    public UpfilesIo(final PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public String getAGBLink() {
        return "https://upfiles.com/page/terms";
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return -1;
    }

    public static List<String[]> getPluginDomains() {
        final List<String[]> ret = new ArrayList<String[]>();
        // each entry in List<String[]> will result in one PluginForDecrypt, Plugin.getHost() will return String[0]->main domain
        ret.add(new String[] { "upfiles.app", "upfiles.io", "upfiles.com" });
        return ret;
    }

    private final List<String> getDeadDomains() {
        return Arrays.asList(new String[] { "upfiles.io" });
    }

    public static String[] getAnnotationNames() {
        return buildAnnotationNames(getPluginDomains());
    }

    @Override
    public String[] siteSupportedNames() {
        return buildSupportedNames(getPluginDomains());
    }

    public static String[] getAnnotationUrls() {
        return buildAnnotationUrls(getPluginDomains());
    }

    public static String[] buildAnnotationUrls(final List<String[]> pluginDomains) {
        final List<String> ret = new ArrayList<String>();
        for (final String[] domains : pluginDomains) {
            ret.add("https?://(?:www\\.)?" + buildHostsPatternPart(domains) + "/([A-Za-z0-9]+)");
        }
        return ret.toArray(new String[0]);
    }

    @Override
    public String rewriteHost(final String host) {
        /* 2022-07-18: Main domain changed from upfiles.io to upfiles.com (upfiles.app) */
        return this.rewriteHost(getPluginDomains(), host);
    }

    private String getContentURL(final DownloadLink link) {
        final String domain;
        final String domainFromURL = Browser.getHost(link.getPluginPatternMatcher());
        if (getDeadDomains().contains(domainFromURL)) {
            domain = this.getHost();
        } else {
            domain = domainFromURL;
        }
        return "https://" + domain + "/" + this.getFID(link);
    }

    @Override
    public String getLinkID(final DownloadLink link) {
        final String linkid = getFID(link);
        if (linkid != null) {
            return this.getHost() + "://" + linkid;
        } else {
            return super.getLinkID(link);
        }
    }

    private String getFID(final DownloadLink link) {
        return new Regex(link.getPluginPatternMatcher(), this.getSupportedLinks()).getMatch(0);
    }

    @Override
    public void handleFree(DownloadLink link) throws Exception {
        // requestFileInformation(link);
        br.getPage(getContentURL(link));
        if (this.br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        String downloadUrl = getDownloadFileUrl(link, MethodName.handleFree);
        URLConnectionAdapter con = null;
        try {
            br.setFollowRedirects(true);
            con = br.openGetConnection(downloadUrl);
            if (this.looksLikeDownloadableContent(con)) {
                logger.info("This url is a directurl");
                if (con.getCompleteContentLength() > 0) {
                    link.setVerifiedFileSize(con.getCompleteContentLength());
                }
                link.setFinalFileName(Encoding.htmlDecode(getFileNameFromHeader(con).trim()));
            } else {
                br.followConnection();
            }
        } finally {
            try {
                con.disconnect();
            } catch (final Throwable e) {
            }
        }
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, downloadUrl, true, 0);
        if (!this.looksLikeDownloadableContent(dl.getConnection())) {
            try {
                br.followConnection(true);
            } catch (final IOException e) {
                logger.log(e);
            }
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl.startDownload();
    }

    enum MethodName {
        requestFileInformation,
        handleFree
    }

    String getDownloadFileUrl(final DownloadLink link, MethodName type) throws Exception {
        final String csrfToken = br.getRegex("csrf-token\" content=\"([^\"]+)\"").getMatch(0);
        UrlQuery query = new UrlQuery();
        query.add("_token", csrfToken);
        query.add("ccp", "1");
        query.add("action", "continue");
        br.postPage(br.getURL(), query);
        final String hcaptchaSiteKey = br.getRegex("\"hcaptcha_checkbox_site_key\":\"([^\"]+)\"").getMatch(0);
        String view_form_data = br.getRegex("view_form_data\" value=\"([^\"]+)\"").getMatch(0);
        UrlQuery query2 = new UrlQuery();
        query2.add("_token", csrfToken);
        query2.add("view_form_data", view_form_data);
        query2.add("action", "captcha");
        final String hcaptchaToken = new CaptchaHelperHostPluginHCaptcha(this, br, hcaptchaSiteKey).getToken();
        query2.add("g-recaptcha-response", Encoding.urlEncode(hcaptchaToken));
        query2.add("h-captcha-response", Encoding.urlEncode(hcaptchaToken));
        br.postPage(br.getURL(), query2);
        UrlQuery query3 = new UrlQuery();
        query3.add("_token", csrfToken);
        query3.add("view_form_data", view_form_data);
        final String waitSecondsStr = br.getRegex("class=\"timer\">\\s*(\\d+)\\s*</span>").getMatch(0);
        final int waitSeconds = Integer.parseInt(waitSecondsStr);
        if (type == MethodName.handleFree) {
            sleep(waitSeconds * 1000l, link);
        } else {
            Thread.sleep(waitSeconds + 100);
        }
        br.postPage("/file/go", query3);
        return PluginJSonUtils.getJsonValue(br, "url");
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        link.setPluginPatternMatcher(link.getPluginPatternMatcher().replace("upfiles.com", "upfiles.io"));
        br.getPage(link.getPluginPatternMatcher());
        if (this.br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        final String directurl = getDownloadFileUrl(link, MethodName.requestFileInformation);
        URLConnectionAdapter con = null;
        try {
            br.setFollowRedirects(true);
            con = br.openGetConnection(directurl);
            if (this.looksLikeDownloadableContent(con)) {
                logger.info("This url is a directurl");
                if (con.getCompleteContentLength() > 0) {
                    link.setVerifiedFileSize(con.getCompleteContentLength());
                }
                link.setFinalFileName(Encoding.htmlDecode(getFileNameFromHeader(con).trim()));
            } else {
                br.followConnection();
            }
        } finally {
            try {
                con.disconnect();
            } catch (final Throwable e) {
            }
        }
        return AvailableStatus.TRUE;
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }

    @Override
    public void resetPluginGlobals() {
    }
}