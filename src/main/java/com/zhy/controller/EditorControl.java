package com.zhy.controller;

import com.zhy.aspect.annotation.PermissionCheck;
import com.zhy.component.StringAndArray;
import com.zhy.constant.CodeType;
import com.zhy.model.Article;
import com.zhy.service.ArticleService;
import com.zhy.service.CategoryService;
import com.zhy.service.TagService;
import com.zhy.service.UserService;
import com.zhy.utils.*;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.security.Principal;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;


/**
 * @author: zhangocean
 * @Date: 2018/6/20 14:25
 * Describe:
 */
@RestController
@Slf4j
public class EditorControl {

    @Autowired
    ArticleService articleService;
    @Autowired
    UserService userService;
    @Autowired
    TagService tagService;
    @Autowired
    CategoryService categoryService;

    /**
     * 发表博客
     * @param principal 当前登录用户
     * @param article 文章
     * @param request httpServletRequest
     * @return
     */
    @PostMapping(value = "/publishArticle", produces = MediaType.APPLICATION_JSON_UTF8_VALUE)
    public String publishArticle(@AuthenticationPrincipal Principal principal,
                                     Article article,
                                     @RequestParam("articleGrade") String articleGrade,
                                     HttpServletRequest request){
        try {
            if(principal == null){
                request.getSession().setAttribute("article", article);
                request.getSession().setAttribute("articleGrade", articleGrade);
                request.getSession().setAttribute("articleTags", request.getParameterValues("articleTagsValue"));
                return JsonResult.fail(CodeType.USER_NOT_LOGIN).toJSON();
            }
            String username = principal.getName();

            String phone = userService.findPhoneByUsername(username);
            if(!userService.isSuperAdmin(phone)){
                return JsonResult.fail(CodeType.PUBLISH_ARTICLE_NO_PERMISSION).toJSON();
            }

            //获得文章html代码并生成摘要
            BuildArticleTabloidUtil buildArticleTabloidUtil = new BuildArticleTabloidUtil();
            String articleHtmlContent = buildArticleTabloidUtil.buildArticleTabloid(request.getParameter("articleHtmlContent"));
            article.setArticleTabloid(articleHtmlContent + "...");

            String[] articleTags = request.getParameterValues("articleTagsValue");
            String[] tags = new String[articleTags.length+1];
            for(int i=0;i<articleTags.length;i++){
                //去掉可能出现的换行符
                articleTags[i] = articleTags[i].replaceAll("<br>", StringUtil.BLANK).replaceAll("&nbsp;",StringUtil.BLANK).replaceAll("\\s+",StringUtil.BLANK).trim();
                tags[i] = articleTags[i];
            }
            tags[articleTags.length] = article.getArticleType();
            //添加标签
            tagService.addTags(tags, Integer.parseInt(articleGrade));
            TimeUtil timeUtil = new TimeUtil();
            String id = request.getParameter("id");
            //修改文章
            if(!StringUtil.BLANK.equals(id) && id != null){
                String updateDate = timeUtil.getFormatDateForThree();
                article.setArticleTags(StringAndArray.arrayToString(tags));
                article.setUpdateDate(updateDate);
                article.setId(Integer.parseInt(id));
                article.setAuthor(username);
                DataMap data = articleService.updateArticleById(article);
                return JsonResult.build(data).toJSON();
            }

            String nowDate = timeUtil.getFormatDateForThree();
            long articleId = timeUtil.getLongTime();

            article.setArticleId(articleId);
            article.setAuthor(username);
            article.setArticleTags(StringAndArray.arrayToString(tags));
            article.setPublishDate(nowDate);
            article.setUpdateDate(nowDate);

            DataMap data = articleService.insertArticle(article);
            return JsonResult.build(data).toJSON();
        } catch (Exception e){
            log.error("Publish article [{}] exception", article.getArticleTitle(), e);
        }
        return JsonResult.fail(CodeType.SERVER_EXCEPTION).toJSON();
    }

    /**
     * 验证是否有权限写博客
     * @param principal
     * @return
     */
    @GetMapping(value = "/canYouWrite", produces = MediaType.APPLICATION_JSON_UTF8_VALUE)
    @PermissionCheck(value = "ROLE_USER")
    public String canYouWrite(@AuthenticationPrincipal Principal principal){

        try {
            String username = principal.getName();
            String phone = userService.findPhoneByUsername(username);
            if(userService.isSuperAdmin(phone)){
                return JsonResult.success().toJSON();
            }
            return JsonResult.fail().toJSON();
        } catch (Exception e){
            log.error("Can you write exception", e);
        }
        return JsonResult.fail(CodeType.SERVER_EXCEPTION).toJSON();
    }

    /**
     * 获得所有的分类
     * @return
     */
    @GetMapping(value = "/findCategoriesName", produces = MediaType.APPLICATION_JSON_UTF8_VALUE)
    public String findCategoriesName(){
        try {
            DataMap data = categoryService.findCategoriesName();
            return JsonResult.build(data).toJSON();
        } catch (Exception e){
            log.error("Find category name exception", e);
        }
        return JsonResult.fail(CodeType.SERVER_EXCEPTION).toJSON();
    }

    /**
     * 获得是否有未发布的草稿文章或是修改文章
     */
    @GetMapping(value = "/getDraftArticle", produces = MediaType.APPLICATION_JSON_UTF8_VALUE)
    @PermissionCheck(value = "ROLE_USER")
    public String getDraftArticle(HttpServletRequest request){
        try {
            String id = (String) request.getSession().getAttribute("id");

            //判断是否为修改文章
            if(id != null){
                request.getSession().removeAttribute("id");
                Article article = articleService.findArticleById(Integer.parseInt(id));
                int lastItem = article.getArticleTags().lastIndexOf(',');
                String[] articleTags = StringAndArray.stringToArray(article.getArticleTags().substring(0, lastItem));
                DataMap data = articleService.getDraftArticle(article, articleTags, tagService.getTagsSizeByTagName(articleTags[0]));
                return JsonResult.build(data).toJSON();
            }
            //判断是否为写文章登录超时
            if(request.getSession().getAttribute("article") != null){
                Article article = (Article) request.getSession().getAttribute("article");
                String[] articleTags = (String[]) request.getSession().getAttribute("articleTags");
                String articleGrade = (String) request.getSession().getAttribute("articleGrade");
                DataMap data =articleService.getDraftArticle(article, articleTags, Integer.parseInt(articleGrade));

                request.getSession().removeAttribute("article");
                request.getSession().removeAttribute("articleTags");
                request.getSession().removeAttribute("articleGrade");
                return JsonResult.build(data).toJSON();
            }
            return JsonResult.fail().toJSON();
        } catch (Exception e){
            log.error("Get draft article exception", e);
        }
        return JsonResult.fail(CodeType.SERVER_EXCEPTION).toJSON();
    }

    /**
     * 文章编辑本地上传图片
     */
    @RequestMapping("/uploadImage")
    public Map<String,Object> uploadImage(HttpServletRequest request, HttpServletResponse response,
                             @RequestParam(value = "editormd-image-file", required = false) MultipartFile file){
        Map<String,Object> resultMap = new HashMap<String,Object>();
        try {
            request.setCharacterEncoding( "utf-8" );
            //设置返回头后页面才能获取返回url
//            response.setHeader("X-Frame-Options", "SAMEORIGIN");
//            FileUtil fileUtil = new FileUtil();
//            String filePath = this.getClass().getResource("/").getPath().substring(1) + "blogImg/";
//            String fileContentType = file.getContentType();
//            String fileExtension = fileContentType.substring(fileContentType.indexOf("/") + 1);
//            TimeUtil timeUtil = new TimeUtil();
//            String fileName = timeUtil.getLongTime() + "." + fileExtension;
             String subCatalog = "blogArticles/" + new TimeUtil().getFormatDateForThree();
//            String fileUrl = fileUtil.uploadFile(fileUtil.multipartFileToFile(file, filePath, fileName), subCatalog);

            String fileUrl = "http://www.54gwz.cn/blogImages/" + subCatalog + "/" + file.getName();

            //获取文件的原始名称
            String originalFilename = file.getOriginalFilename();
            //获取文件的属性名称
            String name = file.getName();
            //获取最后一个.的索引
            int index = originalFilename.lastIndexOf(".");
            //截取文件的.后缀，如.png
            String suffixString = originalFilename.substring(index);
            //生成随笔码
            UUID uuid = UUID.randomUUID();

            Date date = new Date();

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy//MM//dd//HH//mm//ss");
            //按路径格式生成时间，作为文件路径
            String dateFormat = sdf.format(date);

            System.out.println(dateFormat);
            //定义文件的全路径
            String rootPath="/Users/finup/Desktop/"+dateFormat;

            File file1 = new File(rootPath);
            //判断文件夹是否存在，不存在则创建
            if (!file1.exists()) {

                file1.mkdirs();
            }
            //定义文件的全路径，包括随机生成的文件名
            String globalPath=rootPath+"//"+uuid.toString()+suffixString;
            //将文件上传到指定的位置
            file.transferTo(new File(globalPath));
            //若上传成功，则将文件名和对应的路径存到map中返回

            resultMap.put("success", 1);
            resultMap.put("message", "上传成功");
            resultMap.put("url", fileUrl);
        } catch (Exception e) {
            try {
                response.getWriter().write( "{\"success\":0}" );
            } catch (IOException e1) {
                e1.printStackTrace();
            }
        }
        return resultMap;
    }
}
