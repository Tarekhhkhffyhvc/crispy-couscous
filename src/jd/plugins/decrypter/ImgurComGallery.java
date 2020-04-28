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
package jd.plugins.decrypter;

import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.appwork.storage.JSonStorage;
import org.appwork.storage.TypeRef;
import org.appwork.utils.StringUtils;
import org.jdownloader.scripting.JavaScriptEngineFactory;

import jd.PluginWrapper;
import jd.config.SubConfiguration;
import jd.controlling.AccountController;
import jd.controlling.ProgressController;
import jd.http.Browser;
import jd.http.Browser.BrowserException;
import jd.nutils.encoding.Encoding;
import jd.nutils.encoding.HTMLEntities;
import jd.parser.Regex;
import jd.plugins.Account;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterException;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.PluginForDecrypt;
import jd.plugins.PluginForHost;
import jd.plugins.hoster.ImgurComHoster;
import jd.utils.JDUtilities;

/*Only accept single-imag URLs with an LID-length or either 5 OR 7 - everything else are invalid links or thumbnails*/
@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "imgur.com" }, urls = { "https?://(?:www\\.|m\\.)?imgur\\.com/(?:gallery|a)/[A-Za-z0-9]{5,7}|https?://(?:www\\.)?i\\.imgur\\.com/(?:download/)?(?:[A-Za-z0-9]{7}|[A-Za-z0-9]{5})|https?://(?:www\\.|m\\.)?imgur\\.com/(?:download/)?(?:[A-Za-z0-9]{7}|[A-Za-z0-9]{5})|https?://(?:www\\.)?imgur\\.com/r/[^/]+/[A-Za-z0-9]{5,7}|https?://(?:www\\.)?imgur\\.com/r/[^/]+/$" })
public class ImgurComGallery extends PluginForDecrypt {
    public ImgurComGallery(PluginWrapper wrapper) {
        super(wrapper);
    }

    /*
     * TODO: Add API handling for this URL type 'subreddit': https://apidocs.imgur.com/?version=latest#98f68034-15a0-4044-a9ac-5ff3dc75c6b0
     */
    private final String            type_subreddit_single_image   = "https?://[^/]+/r/[^/]+/([A-Za-z0-9]{5,7})";
    private final String            type_subreddit_gallery        = "https?://[^/]+/r/[^/]+/$";
    private final String            type_album                    = "https?://[^/]+/a/[A-Za-z0-9]{5,7}";
    private final String            type_gallery                  = "https?://[^/]+/gallery/([A-Za-z0-9]{5,7})";
    /* User settings */
    private static final String     SETTING_USE_API               = "SETTING_USE_API";
    private static final String     SETTING_GRAB_SOURCE_URL_VIDEO = "SETTING_GRAB_SOURCE_URL_VIDEO";
    private static final String     API_FAILED                    = "API_FAILED";
    /* Constants */
    private static long             VIEW_FILESIZELIMIT            = 0;
    private static Object           CTRLLOCK                      = new Object();
    private ArrayList<DownloadLink> decryptedLinks                = new ArrayList<DownloadLink>();
    private String                  parameter                     = null;
    private String                  itemID                        = null;
    private String                  author                        = null;
    private String                  videoSource                   = null;
    private boolean                 grabVideoSource               = false;

    @SuppressWarnings("deprecation")
    public ArrayList<DownloadLink> decryptIt(CryptedLink param, ProgressController progress) throws Exception {
        final SubConfiguration cfg = SubConfiguration.getConfig("imgur.com");
        parameter = param.toString().replace("://m.", "://").replace("https://", "http://").replaceFirst("/all$", "");
        itemID = new Regex(parameter, "([A-Za-z0-9]+)$").getMatch(0);
        grabVideoSource = cfg.getBooleanProperty(SETTING_GRAB_SOURCE_URL_VIDEO, ImgurComHoster.defaultSOURCEVIDEO);
        final Account account = AccountController.getInstance().getValidAccount(this.getHost());
        final PluginForHost hostPlg = JDUtilities.getPluginForHost(this.getHost());
        boolean loggedIN = false;
        if (account != null) {
            try {
                ((ImgurComHoster) hostPlg).login(this.br, account, false);
                loggedIN = true;
            } catch (final Throwable e) {
                logger.log(e);
            }
        }
        synchronized (CTRLLOCK) {
            VIEW_FILESIZELIMIT = ImgurComHoster.view_filesizelimit;
            String galleryTitle = null;
            String fpName = null;
            if (parameter.matches(type_subreddit_gallery) && !parameter.matches(type_subreddit_single_image)) {
                /* Subreddit image-list --> Website-only */
                br.getPage(parameter);
                br.followRedirect();
                final String id = new Regex(parameter, "/r/([^/]+)/").getMatch(0);
                int page = 1;
                do {
                    final String[] type_image_list_links = br.getRegex("\"image-list-link\"\\s*href\\s*=\\s*\"(/r/[^/]+/[A-Za-z0-9]{5,7})\"").getColumn(0);
                    if (type_image_list_links != null && type_image_list_links.length > 0) {
                        for (String type_image_list_link : type_image_list_links) {
                            final DownloadLink dl = createDownloadlink(br.getURL(type_image_list_link).toString());
                            dl.setContainerUrl(parameter);
                            decryptedLinks.add(dl);
                        }
                        br.getPage("/r/" + id + "/new/page/" + page++ + "/hit?scrolled");
                    } else {
                        break;
                    }
                } while (!isAbort());
                return decryptedLinks;
            } else if (parameter.matches(type_album) || parameter.matches(type_gallery)) {
                /* Album/Gallery --> API required */
                try {
                    if (loggedIN && !cfg.getBooleanProperty(SETTING_USE_API, true)) {
                        logger.info("User prefers not to use the API --> Cannot crawl this type of URL without API");
                        throw new DecrypterException(API_FAILED);
                    }
                    if (!loggedIN) {
                        /* Anonymous API usage */
                        ImgurComHoster.prepBRAPI(this.br);
                        br.getHeaders().put("Authorization", ImgurComHoster.getAuthorization());
                    }
                    try {
                        /* Gallery (single image) */
                        if (parameter.matches(type_gallery)) {
                            br.getPage(ImgurComHoster.getAPIBaseWithVersion() + "/gallery/" + itemID);
                            if (br.getHttpConnection().getResponseCode() == 404) {
                                /*
                                 * Either it is a gallery with a single photo or it is offline. Seems like there is no way to know this
                                 * before!
                                 */
                                final DownloadLink dl = createDownloadlink(getHostpluginurl(itemID));
                                decryptedLinks.add(dl);
                                return decryptedLinks;
                            }
                            boolean is_album = false;
                            final String is_albumo = getJson(br.toString(), "is_album");
                            if (is_albumo != null) {
                                is_album = Boolean.parseBoolean(is_albumo);
                            }
                            if (parameter.matches(type_gallery) && is_album) {
                                /* We have a single picture and not an album. */
                                final DownloadLink dl = createDownloadlink(getHostpluginurl(itemID));
                                decryptedLinks.add(dl);
                                return decryptedLinks;
                            }
                        }
                        /* We know that we definitly have an album --> Crawl it */
                        br.getPage(ImgurComHoster.getAPIBaseWithVersion() + "/album/" + itemID);
                        if (br.getHttpConnection().getResponseCode() == 404) {
                            /*
                             * Either it is a gallery with a single photo or it is offline. Seems like there is no way to know this before!
                             */
                            final DownloadLink dl = createDownloadlink(getHostpluginurl(itemID));
                            decryptedLinks.add(dl);
                            return decryptedLinks;
                        }
                    } catch (final BrowserException e) {
                        if (br.getHttpConnection().getResponseCode() == 429) {
                            logger.info("API limit reached, using site");
                            /* TODO: Set AccountError if this happens! */
                            if (loggedIN) {
                            }
                            throw new DecrypterException(API_FAILED);
                        }
                        logger.info("Server problems: " + parameter);
                        throw e;
                    }
                    if (br.getHttpConnection().getResponseCode() == 403 || br.getHttpConnection().getResponseCode() == 404) {
                        return createOfflineLink(parameter);
                    }
                    final Map<String, Object> data = JSonStorage.restoreFromString(br.toString(), TypeRef.HASHMAP);
                    galleryTitle = (String) JavaScriptEngineFactory.walkJson(data, "data/title");
                    api_decrypt(data);
                } catch (final DecrypterException e) {
                    /* Make sure we only continue if the API failed or was disabled by the user. */
                    if (!e.getMessage().equals(API_FAILED)) {
                        throw e;
                    }
                    prepBRWebsite(this.br);
                    br.getPage(parameter);
                    if (br.getHttpConnection().getResponseCode() == 404 || br.containsHTML("class=\"textbox empty\"|<h1>Zoinks! You've taken a wrong turn\\.</h1>|it's probably been deleted or may not have existed at all\\.</p>")) {
                        return createOfflineLink(parameter);
                    }
                    author = br.getRegex("property=\"author\" content=\"([^<>\"]*?)\"").getMatch(0);
                    if (author != null && StringUtils.equalsIgnoreCase(author, "Imgur")) {
                        author = null;
                    }
                    galleryTitle = br.getRegex("<title>([^<>\"]*?) \\-(?: Album on)? Imgur</title>").getMatch(0);
                    if (galleryTitle == null) {
                        galleryTitle = br.getRegex("<meta property=\"og:title\" content=\"([^<>\"]+)\"").getMatch(0);
                    }
                    /* 2015-12-07: The few lines of code below seem not to work anymore/not needed anymore. */
                    // final String album_info = br.getRegex("\"album_images\":\\{(.+)").getMatch(0);
                    // if (album_info != null) {
                    // final String count_pics_str = new Regex(album_info, "\"count\":(\\d+)").getMatch(0);
                    // /* Only load that if needed - it e.g. won't work for galleries with only 1 picture. */
                    // if (count_pics_str != null && Long.parseLong(count_pics_str) >= 10) {
                    // logger.info("siteDecrypt: loading json to get all pictures");
                    // br.getHeaders().put("Accept", "application/json, text/javascript, */*; q=0.01");
                    // br.getHeaders().put("X-Requested-With", "XMLHttpRequest");
                    // br.getPage("http://imgur.com/gallery/" + LID + "/album_images/hit.json?all=true");
                    // br.getRequest().setHtmlCode(br.toString().replace("\\", ""));
                    // }
                    // }
                    site_decrypt();
                }
                if (galleryTitle != null) {
                    galleryTitle = Encoding.htmlDecode(galleryTitle).trim();
                }
                fpName = ImgurComHoster.getFormattedPackagename(author, galleryTitle, itemID);
                fpName = encodeUnicode(fpName);
                final FilePackage fp = FilePackage.getInstance();
                fp.setName(fpName.trim());
                fp.addLinks(decryptedLinks);
            } else {
                /* Single images --> Host plugin without requiring any HTTP requests */
                final DownloadLink dl = createDownloadlink(getHostpluginurl(itemID));
                decryptedLinks.add(dl);
            }
        }
        return decryptedLinks;
    }

    private String getHostpluginurl(final String lid) {
        return "http://imgurdecrypted.com/download/" + lid;
    }

    private void api_decrypt(Map<String, Object> data) throws DecrypterException, ParseException {
        final boolean user_prefers_mp4 = ImgurComHoster.userPrefersMp4();
        long status = JavaScriptEngineFactory.toLong(data.get("status"), 200);
        if (status == 404) {
            /* Well in case it's a gallery link it might be a single picture */
            if (parameter.matches(type_gallery)) {
                final DownloadLink dl = createDownloadlink(getHostpluginurl(itemID));
                decryptedLinks.add(dl);
                return;
            }
            decryptedLinks.addAll(createOfflineLink(parameter));
            return;
        }
        final long imgcount = JavaScriptEngineFactory.toLong(JavaScriptEngineFactory.walkJson(data, "data/images_count"), 0);
        Object images = JavaScriptEngineFactory.walkJson(data, "data/images");
        /* Either no images there or sometimes the number of wrong. */
        if (imgcount == 0 || images == null || ((List) images).size() == 0) {
            logger.info("Empty album: " + parameter);
            return;
        }
        author = (String) JavaScriptEngineFactory.walkJson(data, "data/account_url");
        if (author != null && StringUtils.equalsIgnoreCase(author, "Imgur")) {
            /* Some errorhandling */
            author = null;
        }
        /*
         * using links (i.imgur.com/imgUID(s)?.extension) seems to be problematic, it can contain 's' (imgUID + s + .extension), but not
         * always! imgUid.endswith("s") is also a valid uid, so you can't strip them!
         */
        final List<Map<String, Object>> items = (List<Map<String, Object>>) images;
        final int padLength = getPadLength(imgcount);
        int itemNumber = 0;
        for (final Map<String, Object> item : items) {
            itemNumber++;
            String directlink = (String) item.get("link");
            String title = (String) item.get("title");
            final String itemnumber_formatted = String.format(Locale.US, "%0" + padLength + "d", itemNumber);
            final long size = JavaScriptEngineFactory.toLong(item.get("size"), -1);
            final long size_mp4 = JavaScriptEngineFactory.toLong(item.get("mp4_size"), -1);
            final String imgUID = (String) item.get("id");
            /* TODO: */
            videoSource = null;
            String filetype = (String) item.get("type");
            if (imgUID == null || (size == -1 && size_mp4 == -1) || directlink == null || filetype == null) {
                logger.warning("Decrypter broken for link: " + parameter);
                throw new DecrypterException("Decrypter broken for link: " + parameter);
            }
            if (filetype.matches("image/[A-Za-z0-9]+")) {
                /* E.g. 'image/gif' --> 'gif' */
                filetype = filetype.split("/")[1];
            }
            if (!inValidate(title)) {
                title = Encoding.htmlDecode(title);
                title = HTMLEntities.unhtmlentities(title);
                title = HTMLEntities.unhtmlAmpersand(title);
                title = HTMLEntities.unhtmlAngleBrackets(title);
                title = HTMLEntities.unhtmlSingleQuotes(title);
                title = HTMLEntities.unhtmlDoubleQuotes(title);
                title = encodeUnicode(title);
            }
            final long filesize;
            if (user_prefers_mp4 && size_mp4 > 0) {
                filesize = size_mp4;
                filetype = "mp4";
            } else {
                filesize = size;
            }
            final DownloadLink dl = createDownloadlink("http://imgurdecrypted.com/download/" + imgUID);
            dl.setAvailable(true);
            dl.setProperty("filetype", filetype);
            /*
             * Note that for pictures/especially GIFs over 20 MB, the "link" value will only contain a link which leads to a preview or low
             * quality version of the picture. This is why we need a little workaround for this case (works from 19.5++ MB).
             */
            if (size >= VIEW_FILESIZELIMIT) {
                directlink = ImgurComHoster.getBigFileDownloadlink(dl);
            }
            dl.setProperty("directlink", directlink);
            dl.setProperty("decryptedfilesize", filesize);
            if (title != null) {
                dl.setProperty("directtitle", title);
            }
            dl.setProperty("directusername", author);
            dl.setProperty("orderid", itemnumber_formatted);
            final String filename = ImgurComHoster.getFormattedFilename(dl);
            dl.setFinalFileName(filename);
            dl.setDownloadSize(filesize);
            /* No need to hide directlinks */
            dl.setContentUrl(ImgurComHoster.getURLContent(imgUID));
            if (videoSource != null && grabVideoSource) {
                decryptedLinks.add(this.createDownloadlink(videoSource));
            }
            decryptedLinks.add(dl);
        }
    }

    private void site_decrypt() throws DecrypterException, ParseException, IOException {
        /* Removed differentiation between two linktypes AFTER revision 26468 */
        if (this.br.containsHTML("class=\"js\\-post\\-truncated\"")) {
            /* RegExes below will work for the json after this ajax request too! */
            this.br.getPage("http://" + this.getHost() + "/ajaxalbums/getimages/" + this.itemID + "/hit.json?all=true");
        }
        String jsonarray = br.getRegex("\"images\":\\[(\\{.*?\\})\\]").getMatch(0);
        if (jsonarray == null) {
            jsonarray = br.getRegex("\"items\":\\[(.*?)\\]").getMatch(0);
        }
        /* Maybe it's just a single image in a gallery */
        if (jsonarray == null) {
            jsonarray = br.getRegex("image\\s*?:\\s*?(\\{.*?\\})").getMatch(0);
        }
        if (jsonarray == null) {
            throw new DecrypterException("Decrypter broken!");
        }
        // final String json_num_images = PluginJSonUtils.getJson(jsonarray, "num_images");
        String[] items = jsonarray.split("\\},\\{");
        /* We assume that the API is always working fine */
        if (items == null || items.length == 0) {
            logger.info("Empty album: " + parameter);
            return;
        }
        final int padLength = getPadLength(items.length);
        int itemNumber = 0;
        for (final String item : items) {
            itemNumber++;
            String directlink;
            String title = getJson(item, "title");
            final String itemnumber_formatted = String.format(Locale.US, "%0" + padLength + "d", itemNumber);
            final String filesize_str = getJson(item, "size");
            final String imgUID = getJson(item, "hash");
            videoSource = getJson(item, "video_source");
            String ext = getJson(item, "ext");
            if (imgUID == null || filesize_str == null || ext == null) {
                logger.info("Seems like user/album has no images at all?!");
                continue;
            }
            final long filesize = Long.parseLong(filesize_str);
            /* Correct sometimes broken ext TODO: Wait for a response of their support - this might be a serverside issue. */
            if (ext.contains("?")) {
                ext = ext.substring(0, ext.lastIndexOf("?"));
            }
            if (!inValidate(title)) {
                title = Encoding.htmlDecode(title);
                title = HTMLEntities.unhtmlentities(title);
                title = HTMLEntities.unhtmlAmpersand(title);
                title = HTMLEntities.unhtmlAngleBrackets(title);
                title = HTMLEntities.unhtmlSingleQuotes(title);
                title = HTMLEntities.unhtmlDoubleQuotes(title);
                title = encodeUnicode(title);
            }
            final DownloadLink dl = createDownloadlink("http://imgurdecrypted.com/download/" + imgUID);
            dl.setDownloadSize(filesize);
            dl.setAvailable(true);
            dl.setProperty("filetype", ext.replace(".", ""));
            /*
             * Note that for pictures/especially GIFs over 20 MB, the "link" value will only contain a link which leads to a preview or low
             * quality version of the picture. This is why we need a little workaround for this case (works from 19.5++ MB).
             */
            if (filesize >= VIEW_FILESIZELIMIT) {
                directlink = "http://" + this.getHost() + "/download/" + imgUID;
                directlink = ImgurComHoster.getBigFileDownloadlink(dl);
            } else {
                directlink = "http://i." + this.getHost() + "/" + imgUID + ext;
            }
            dl.setProperty("directlink", directlink);
            dl.setProperty("decryptedfilesize", filesize);
            dl.setProperty("directtitle", title);
            dl.setProperty("directusername", author);
            dl.setProperty("orderid", itemnumber_formatted);
            final String filename = ImgurComHoster.getFormattedFilename(dl);
            dl.setFinalFileName(filename);
            /* No need to hide directlinks */
            dl.setContentUrl(ImgurComHoster.getURLContent(imgUID));
            if (videoSource != null && grabVideoSource) {
                decryptedLinks.add(this.createDownloadlink(videoSource));
            }
            decryptedLinks.add(dl);
        }
    }

    private final int getPadLength(final long size) {
        if (size < 10) {
            return 1;
        } else if (size < 100) {
            return 2;
        } else if (size < 1000) {
            return 3;
        } else if (size < 10000) {
            return 4;
        } else if (size < 100000) {
            return 5;
        } else if (size < 1000000) {
            return 6;
        } else if (size < 10000000) {
            return 7;
        } else {
            return 8;
        }
    }

    private Browser prepBRWebsite(final Browser br) {
        br.setLoadLimit(br.getLoadLimit() * 2);
        ImgurComHoster.prepBRWebsite(br);
        return br;
    }

    /** TODO: Replace this with PluginJsonUtils */
    private String getJson(final String source, final String parameter) {
        String result = new Regex(source, "\"" + parameter + "\":([\t\n\r ]+)?([0-9\\.]+)").getMatch(1);
        if (result == null) {
            result = new Regex(source, "\"" + parameter + "\":([\t\n\r ]+)?\"([^<>\"]*?)\"").getMatch(1);
        }
        return result;
    }

    /**
     * Validates string to series of conditions, null, whitespace, or "". This saves effort factor within if/for/while statements
     *
     * @param s
     *            Imported String to match against.
     * @return <b>true</b> on valid rule match. <b>false</b> on invalid rule match.
     * @author raztoki
     */
    protected boolean inValidate(final String s) {
        if (s == null || s.matches("\\s+") || s.equals("")) {
            return true;
        } else {
            return false;
        }
    }

    private ArrayList<DownloadLink> createOfflineLink(final String link) {
        ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();
        final DownloadLink offline = createDownloadlink("directhttp://" + link);
        offline.setAvailable(false);
        if (itemID != null) {
            offline.setFinalFileName(itemID);
        }
        offline.setProperty("OFFLINE", true);
        decryptedLinks.add(offline);
        return decryptedLinks;
    }
}
