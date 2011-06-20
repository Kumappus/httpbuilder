package groovyx.net.http

import groovyx.net.http.support.MultiPartFileUploadServer
import static groovyx.net.http.Method.*
import static groovyx.net.http.ContentType.*

import java.security.MessageDigest

import org.apache.http.entity.mime.MultipartEntity
import org.apache.http.entity.mime.HttpMultipartMode
import org.apache.http.entity.mime.content.FileBody
import org.apache.http.entity.mime.content.StringBody
import org.apache.log4j.Logger

import spock.lang.*


class HttpMultiPartUploadSpec extends Specification {

    private static final Logger log = Logger.getLogger(HttpMultiPartUploadSpec.class)

    static final int MULTIPART_SERVER_PORT = 12345
    static final String SOURCE_PATH = 'src/test/resources/upload'
    static final String TARGET_PATH = 'target/upload'


    @AutoCleanup("stop")
    @Shared server = new MultiPartFileUploadServer()

    @Shared httpBuilder = new HTTPBuilder("http://localhost:$MULTIPART_SERVER_PORT")

    def setupSpec() {
        File.metaClass.getMd5 = {
            MessageDigest md = MessageDigest.getInstance('MD5')
            BigInteger number = new BigInteger(1, md.digest(delegate.newInputStream().bytes))
            number.toString(16).padLeft(32, '0')
        }
        server.start(MULTIPART_SERVER_PORT, TARGET_PATH)
        assert server.cleanTarget(), "if the filupload target is not clean we shouldn't even start"
        assert server.started, 'server should be started.'
    }

    //------------------------------------------------------------------------------------------------------------------
    @Unroll("multipart POST upload of file #afile ")
    def "fileupload"() {
        given: "a file we want to upload"
        assert afile.exists(), "the file ${afile.path} should exist"

        when: "we upload the file"
        def aresponse
        httpBuilder.request(POST, afile) { req ->
            uri.path = MultiPartFileUploadServer.URL_UPLOAD_PATH
            requestContentType = ContentType.MULTIPART_FORM

            MultipartEntity entity = new MultipartEntity(HttpMultipartMode.BROWSER_COMPATIBLE)
            entity.addPart("${afile.name}", new FileBody(afile))
            entity.addPart('param_1', new StringBody("One test Parameter"))
            req.setEntity(entity)

            response.success = { resp ->
                log.info "response [ ${resp.status }:${resp.statusLine} ]"
                aresponse = resp
            }
            response.failure = { resp ->
                log.info "response [ ${resp.status }:${resp.statusLine} ]"
                aresponse = resp
            }
        }

        and: "get the uploaded file"
        File target = new File("$TARGET_PATH/${afile.name}")

        then:"the request should be successful"
        aresponse.status == 200

        and: "the MD5 of the uploaded file must match the one from the original"
        afile.md5 == target.md5

        where:
        afile << (SOURCE_PATH as File).listFiles({ File file -> !file.directory } as FileFilter)
    }


    @Unroll("enhanced multipart POST upload of file #afile ")
    def "enhanced fileupload"() {
        given: "a file we want to upload"
        assert afile.exists(), "the file ${afile.path} should exist"

        when: "we upload the file"
        def aresponse
        httpBuilder.post(
                path: MultiPartFileUploadServer.URL_UPLOAD_PATH,
                requestContentType: MULTIPART_FORM,
                body: ["enhanced-${afile.name}": afile, param_1: 'One test Parameter']
        ) { response ->
            log.info "response [ ${response.status }:${response.statusLine} ]"
            aresponse = response
        }

        and: "get the uploaded file"
        File target = new File("$TARGET_PATH/enhanced-${afile.name}")

        then:"the request should be successful"
        aresponse.status == 200

        and: "the MD5 of the uploaded file must match the one from the original"
        afile.md5 == target.md5

        where:
        afile << (SOURCE_PATH as File).listFiles({ File file -> !file.directory } as FileFilter)
    }
}