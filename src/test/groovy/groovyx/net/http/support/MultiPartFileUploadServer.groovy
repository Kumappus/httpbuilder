package groovyx.net.http.support

import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.ExecutorService
import java.util.concurrent.locks.Lock
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

import javax.servlet.http.HttpServlet
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

import org.apache.log4j.Logger

import org.mortbay.jetty.*
import org.mortbay.jetty.servlet.*

class MultiPartFileUploadServer {

    private static final Logger log = Logger.getLogger(MultiPartFileUploadServer)

    static final String DEFAULT_TARGET_PATH = 'target/upload'
    static final String URL_UPLOAD_PATH = '/fileupload'
    static final int DEFAULT_PORT = 12345
    static final long STOP_WAIT = 1000l
    static final long LOCK_WAIT = 500l


    private Lock lock = new ReentrantLock()
    private ExecutorService executor
    private Future future
    private def server

    def start(int port = DEFAULT_PORT,
              String targetPath = DEFAULT_TARGET_PATH) {
        log.info "Attempting to start the multipart http server on port [$port] with targetPath [$targetPath]"
        lock.tryLock(500, TimeUnit.MILLISECONDS)
        try {
            if (this.executor && !this.executor.shutdown) {
                throw new IllegalStateException("service running on port [$port], please stop first.")
            }
            if (future && !future.done) {
                this.future.cancel(true)
            }
            this.server = new JettyHttpServer(port, targetPath)
            this.executor = Executors.newSingleThreadExecutor()
            this.future = executor.submit({ server.start() } as Runnable)

        } finally {
            lock.unlock()
        }
    }

    def stop(long wait = STOP_WAIT) {
        lock.tryLock(500, TimeUnit.MILLISECONDS)
        try {
            if (!this.server) {
                return
            }
            log.info "attempting to shutdown server on port [${server?.port}]"
            if (server?.running) {
                log.info "stopping server on port ${server.port}..."
                server.stop()
            }
            if (this.future && !this.future.done) {
                try {
                    log.info "waiting for server to stop..."
                    this.future.get(2000, TimeUnit.MILLISECONDS)
                } catch (e) {
                    log.error "error while waiting for the server to stop.", e
                }
            }
            if (!this.executor) {
                log.info "shutting down the executor."
                executor.shutdown()
            }
            log.info "shutdown completed for server on port [${server?.port}]"
        } finally {
            lock.unlock()
        }
    }


    boolean isRunning() {
        lock.tryLock(500, TimeUnit.MILLISECONDS)
        try {
            return server?.running ?: false
        } finally {
            lock.unlock()
        }
    }

    boolean isStarted() {
        lock.tryLock(500, TimeUnit.MILLISECONDS)
        try {
            return server?.started ?: false
        } finally {
            lock.unlock()
        }
    }

    boolean isTargetEmpty() {
        File targetFile = server.targetPath as File
        if (!targetFile.exists()) {
            return true
        }

        targetFile.list().size() == 0
    }

    boolean cleanTarget() {
        File targetFile = server.targetPath as File
        if (!targetFile.exists()) {
            return true
        }

        assert targetFile.directory, "Something is wrong, the $targetFile is not a directory."
        try {
            log.info "clearing contents inside ${targetFile.path}..."
            targetFile.listFiles()*.delete()
            log.info "contents inside ${targetFile.path} were removed ${targetFile.size() > 0 ? '' : targetFile.listFiles()}"
        } catch (e) {
            log.warn "unable to clean all files", e
        }

        this.targetEmpty
    }




    private static class JettyHttpServer {

        private static final Logger log = Logger.getLogger(JettyHttpServer);

        final private def server
        final int port
        final String targetPath

        JettyHttpServer(int port,
                        String targetPath,
                        String resourceBase = '.',
                        int gracefulShutdown = 1000) {

            this.port = port
            this.targetPath = targetPath

            server = new Server(this.port)
            server.stopAtShutdown = true
            server.gracefulShutdown = gracefulShutdown

            def root = new Context(server, "/", Context.SESSIONS)
            root.setResourceBase(resourceBase)
            root.addServlet(
                    new ServletHolder(
                            new HttpServlet() {

                                protected void doGet(HttpServletRequest req,
                                                     HttpServletResponse resp) {
                                    log.debug "Request:\n$req"

                                    try {
                                        def uploadErrors = []
                                        def uploadSuccess = []
                                        for (String name: req.attributeNames) {
                                            if (req.getAttribute(name) instanceof File) {
                                                File file = req.getAttribute(name) as File
                                                if (!file || !file.exists()) {
                                                    uploadErrors << "File $name does not exist"
                                                }
                                                else if (file.isDirectory()) {
                                                    uploadErrors << "File $name is a directory"
                                                }
                                                else {
                                                    File targetDir = targetPath as File
                                                    targetDir.mkdirs()
                                                    assert targetDir.exists(), "path [$targetDir] doesn't exist and we were unable to create it!"
                                                    File targetFile = new File(targetPath, name)
                                                    log.debug "renaming file to :\n${targetFile.path}"
                                                    file.renameTo(targetFile)
                                                    uploadSuccess << "File successfully uploaded ${targetFile.path}."
                                                }
                                            }
                                        }
                                        resp.setStatus(
                                                uploadErrors.size() == 0 ? HttpServletResponse.SC_OK : HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                                                [
                                                        parameters: req.parameterNames.toList().join(','),
                                                        sucess: uploadSuccess.join('\n'),
                                                        errors: uploadErrors.join('\n')
                                                ] as String
                                        )

                                    } catch (e) {
                                        resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.message)
                                    }
                                }

                                protected void doPost(HttpServletRequest req, HttpServletResponse resp) {
                                    doGet(req, resp)
                                }
                            }
                    ),
                    URL_UPLOAD_PATH
            )
            def multiPartFilterHolder = new FilterHolder(new org.mortbay.servlet.MultiPartFilter())
            multiPartFilterHolder.setInitParameters(['deleteFiles': 'true'])
            root.addFilter(multiPartFilterHolder, '/fileupload', 0)
        }

        private String getTarget() {
            this.targetPath
        }

        synchronized def start() {
            server.start()
        }

        synchronized def stop() {
            server.stop()
        }

        synchronized boolean isRunning() {
            server.running
        }

        synchronized boolean isStarted(){
            server.started
        }
    }
}
