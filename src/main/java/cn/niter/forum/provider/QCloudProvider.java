package cn.niter.forum.provider;

import cn.niter.forum.cache.TinifyPngCache;
import cn.niter.forum.dto.UserDTO;
import cn.niter.forum.exception.CustomizeErrorCode;
import cn.niter.forum.exception.CustomizeException;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.exception.CosClientException;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.model.PutObjectResult;
import com.qcloud.cos.model.StorageClass;
import com.qcloud.cos.region.Region;
import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.common.exception.TencentCloudSDKException;
import com.tencentcloudapi.common.profile.ClientProfile;
import com.tencentcloudapi.common.profile.HttpProfile;
import com.tencentcloudapi.nlp.v20190408.NlpClient;
import com.tencentcloudapi.nlp.v20190408.models.KeywordsExtractionRequest;
import com.tencentcloudapi.nlp.v20190408.models.KeywordsExtractionResponse;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import sun.misc.BASE64Encoder;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.List;
/**
 * @author wadao
 * @version 2.0
 * @date 2020/5/1 15:17
 * @site niter.cn
 */
@Service
@Slf4j
public class QCloudProvider {
    @Value("${qcloud.secret-id}")
    private String secretId;

    @Value("${qcloud.secret-key}")
    private String secretKey;

    // bucket名需包含appid
    @Value("${qcloud.cos.bucket-name}")
    private String bucketName;

    @Value("${qcloud.cos.region}")
    private String region;

    @Value("${qcloud.cos.objecturl}")
    private String objecturl;

    @Value("${qcloud.ci.objecturl}")
    private String ciObjecturl;

    @Value("${site.main.domain}")
    private String domain;

    @Value("${qcloud.ci.enable}")
    private int ciEnable;

    @Value("${tinify.enable}")
    private int tinifyEnable;

    @Value("${tinify.minContentLength}")
    private Long tinifyMinContentLength;

    @Autowired
    private TinifyPngCache tinifyPngCache;

    public String upload(InputStream fileStream, String contentType, UserDTO user, String fileName , Long contentLength) {

      /*  if("image/png".equals(contentType)&&tinifyEnable==1&&contentLength>tinifyMinContentLength){//进行图片质量压缩（png），若不处理请忽略
            try {
                fileStream = tinifyProvider.getStreamfromInputStream(fileStream);
                contentLength = (long)fileStream.available();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }*/

        String initUrl = uploadtoBucket(fileStream,"img",contentType,user,fileName,contentLength);
        String imgUrl=initUrl;
        if("image/png".equals(contentType)&&tinifyEnable==1&&contentLength>tinifyMinContentLength){//进行图片质量压缩（png），若不处理请忽略
            System.out.println("add:"+imgUrl);
            tinifyPngCache.add(imgUrl,user,fileName);
        }
        if(ciEnable==1){//开启腾讯云数据万象后可以生成水印，进行图片审核与质量压缩（jpg），若不处理请忽略
            ImageInfo imageInfo = getImageInfo(imgUrl);
            //大于400*150才生成水印
            if(Integer.parseInt(imageInfo.getWidth())>400&&Integer.parseInt(imageInfo.getHeight())>150){
                String watermark = "@"+user.getName()+" "+domain;
                try {
                    byte[] data = watermark.getBytes("utf-8");
                    String encodeBase64 = new BASE64Encoder().encode(data);
                    watermark = encodeBase64.replace('+', '-');
                    watermark = watermark.replace('/', '_');
                    //watermark = watermark.replaceAll("=", "");
                    //watermark= Base64.getEncoder().encodeToString(data);
                } catch (UnsupportedEncodingException e) {
                    e.printStackTrace();
                }
                imgUrl = initUrl+"?imageView2/q/75|watermark/2/text/"+watermark+"/fill/IzNEM0QzRA/fontsize/18/dissolve/80/gravity/SouthEast/dx/20/dy/10/";

                //initUrl = initUrl+"?imageView2/q/75|watermark/2/text/"+watermark+"/fill/IzNEM0QzRA/fontsize/18/dissolve/80/gravity/SouthEast/dx/20/dy/10/";
                //imgUrl=uploadUrltoBucket(initUrl,"img",contentType,user,fileName);
            }
            imgUrl = imgUrl.replace(objecturl,ciObjecturl);
        }


        return imgUrl;
    }

    public String uploadAvatar(InputStream inputStream, String contentType, UserDTO user, String fileName, Long contentLength)  {
        String initUrl = uploadtoBucket(inputStream,"avatar",contentType,user,fileName,contentLength);
        String avatarUrl=initUrl;
        //开启腾讯云数据万象后可以上传的头像进行智能剪切，若不处理请忽略
        if(ciEnable==1){
        initUrl = initUrl+"?imageMogr2/scrop/168x168/crop/168x168/gravity/center";
        avatarUrl=uploadUrltoBucket(initUrl,"avatar",contentType,user,fileName);
        //initUrl = initUrl+"?imageMogr2/crop/168x168/gravity/center";
        //avatarUrl=uploadUrltoBucket(initUrl,"avatar",contentType,user,fileName);
        }
        return avatarUrl;
    }

    private String uploadUrltoBucket(String initUrl, String fileType, String contentType, UserDTO user, String fileName)  {
        URL url = null;
        String finalUrl=null;
        InputStream fileStream=null;
        try {
            url = new URL(initUrl);
            URLConnection con = url.openConnection();
            //设置请求超时为5s
            con.setConnectTimeout(5*1000);
            // 输入流
            fileStream = con.getInputStream();
            finalUrl = uploadtoBucket(fileStream,fileType,contentType,user,fileName, Long.valueOf(con.getContentLength()));
        } catch (MalformedURLException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }finally {//解决流资源未释放的问题
            if(fileStream!=null){
                try {
                    fileStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        return finalUrl;
    }

    public String uploadtoBucket(InputStream inputStream, String fileType, String contentType, UserDTO user, String fileName, Long contentLength){

        // 1 初始化用户身份信息(secretId, secretKey)
        COSCredentials cred = new BasicCOSCredentials(secretId, secretKey);
        // 2 设置bucket的区域, COS地域的简称请参照 https://www.qcloud.com/document/product/436/6224
        ClientConfig clientConfig = new ClientConfig(new Region(region));
        // 3 生成cos客户端
        COSClient cosclient = new COSClient(cred, clientConfig);

        String key = "upload/user/"+user.getId()+"/"+fileType+"/"+fileName;

        ObjectMetadata objectMetadata = new ObjectMetadata();
        // 从输入流上传必须制定content length, 否则http客户端可能会缓存所有数据，存在内存OOM的情况
        objectMetadata.setContentLength(contentLength);
        // 默认下载时根据cos路径key的后缀返回响应的contenttype, 上传时设置contenttype会覆盖默认值
        objectMetadata.setContentType("contentType");

        PutObjectRequest putObjectRequest =
                new PutObjectRequest(bucketName, key, inputStream, objectMetadata);
        // 设置存储类型, 默认是标准(Standard), 低频(standard_ia)
        putObjectRequest.setStorageClass(StorageClass.Standard);
        try {
            PutObjectResult putObjectResult = cosclient.putObject(putObjectRequest);
            // putobjectResult会返回文件的etag
            String etag = putObjectResult.getETag();
            //System.out.println(etag);
        } catch (CosServiceException e) {
            //e.printStackTrace();
            log.error("upload error,{}", key, e);
            throw new CustomizeException(CustomizeErrorCode.FILE_UPLOAD_FAIL);
        } catch (CosClientException e) {
            //e.printStackTrace();
            log.error("upload error,{}", key, e);
            throw new CustomizeException(CustomizeErrorCode.FILE_UPLOAD_FAIL);
        }
        // 关闭客户端
        cosclient.shutdown();
        return objecturl+key;
    }

    private ImageInfo getImageInfo(String url){
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
                .url(url+"?imageInfo")
                .build();
        ImageInfo imageInfo=null;
        try {
            Response response = client.newCall(request).execute();
            String string = response.body().string();
            System.out.println(string);
            imageInfo = JSON.parseObject(string, ImageInfo.class);
        } catch (IOException e) {
            e.printStackTrace();
        }

             return imageInfo;
    }

    public String getKeywords(String text , int num , double score){
        String keyWordString = "";
        try{

            Credential cred = new Credential(secretId, secretKey);

            HttpProfile httpProfile = new HttpProfile();
            httpProfile.setEndpoint("nlp.tencentcloudapi.com");

            ClientProfile clientProfile = new ClientProfile();
            clientProfile.setHttpProfile(httpProfile);

            NlpClient client = new NlpClient(cred, "ap-guangzhou", clientProfile);

            String params = "{\"Num\":"+num+",\"Text\":\""+text+"\"}";
            KeywordsExtractionRequest req = KeywordsExtractionRequest.fromJsonString(params, KeywordsExtractionRequest.class);

            KeywordsExtractionResponse resp = client.KeywordsExtraction(req);
            JSONObject obj= JSON.parseObject(KeywordsExtractionRequest.toJsonString(resp));
            JSONArray keywords = obj.getJSONArray("Keywords");
            List<Keywords> keywordsList = JSONObject.parseArray(keywords.toJSONString(), Keywords.class);

            if(keywordsList.size()>0)
                for (Keywords keyword : keywordsList) {
                    if(keyword.getScore()>score) keyWordString=keyWordString+","+keyword.getWord();
                }
            //System.out.println("keyWordString:"+keyWordString.substring(1));
            //System.out.println(KeywordsExtractionRequest.toJsonString(resp));
        } catch (TencentCloudSDKException e) {
            System.out.println(e.toString());
        }
        return keyWordString;//返回格式  ,k1,k2,k3,k4.....,kn
    }


    @Data
    static class  Keywords{
        Double Score;
        String Word;
    }

    @Data
    static class  ImageInfo{
        String format;
        String width;
        String height;
        String size;
        String md5;
        String photo_rgb;
    }

}
