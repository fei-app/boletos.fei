package com.marinov.boletosfei

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.webkit.CookieManager
import androidx.core.content.FileProvider
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class SessionExpiredException(message: String) : Exception(message)

object Dados {

    private const val PREFS_NAME = "DadosFEI"
    private const val KEY_PERFIL = "perfil_cache"
    private const val KEY_LAST_UPDATE_PERFIL = "last_update_perfil"
    private const val KEY_BOLETOS = "boletos_cache"
    private const val KEY_LAST_UPDATE_BOLETOS = "last_update_boletos"

    private const val URL_PERFIL = "https://interage.fei.org.br/secureserver/portal/graduacao/secretaria/dados-pessoais"
    private const val URL_BOLETOS = "https://interage.fei.org.br/secureserver/portal/graduacao/tesouraria/consultas/boletos"
    private const val URL_GERAR_BOLETO = "https://interage.fei.org.br/secureserver/portal/graduacao/tesouraria/consultas/boletos/titulos/gerar"

    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 16; sdk_gphone64_x86_64 Build/BE2A.250530.026.D1; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.6943.137 Mobile Safari/537.36"

    private lateinit var appContext: Context
    private val gson = Gson()
    private val prefs: SharedPreferences by lazy { appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // ===================== MODELOS DE DADOS =====================

    data class Perfil(
        val nome: String,
        val matricula: String,
        val curso: String,
        val email: String
    )

    /**
     * Representa um boleto/título de cobrança.
     * @param vencimento Data de vencimento (ex: "08/05/2026")
     * @param status     "PAGO" ou "ABERTO"
     * @param dataPagamento Data em que foi pago (vazia quando ABERTO)
     * @param tituloId   Valor do checkbox "titulos" no formulário; vazio para PAGO
     */
    data class Boleto(
        val vencimento: String,
        val status: String,
        val dataPagamento: String,
        val tituloId: String
    )

    // ===================== FUNÇÕES PÚBLICAS =====================

    suspend fun retornaDadosUsuario(online: Boolean): Perfil {
        return if (online) {
            try {
                val perfil = fetchPerfilFromServer()
                savePerfilCache(perfil)
                perfil
            } catch (e: SessionExpiredException) {
                throw e
            } catch (e: Exception) {
                if (e !is CancellationException) Log.e("Dados", "Erro ao buscar perfil online", e)
                getCachedPerfil() ?: Perfil("", "", "", "")
            }
        } else {
            getCachedPerfil() ?: Perfil("", "", "", "")
        }
    }

    // ===================== BOLETOS =====================

    /**
     * Baixa a lista de boletos do servidor (ou do cache se offline) e a salva localmente.
     * @param online Se true, busca do servidor; se false, retorna o cache.
     * @return Lista de boletos com vencimento, status, data de pagamento e id do título.
     */
    suspend fun getBoletos(online: Boolean): List<Boleto> {
        return if (online) {
            try {
                val boletos = fetchBoletosFromServer()
                saveBoletosCache(boletos)
                boletos
            } catch (e: SessionExpiredException) {
                throw e
            } catch (e: Exception) {
                if (e !is CancellationException) Log.e("Dados", "Erro ao buscar boletos online", e)
                getCachedBoletos()
            }
        } else {
            getCachedBoletos()
        }
    }

    /**
     * Verifica se houve alteração nos boletos comparando os dados online com o cache.
     * Salva o cache caso haja alteração.
     * @return true se houver qualquer diferença (novo boleto, mudança de status, etc.)
     */
    suspend fun atualizaBoletos(): Boolean {
        return try {
            val novos = fetchBoletosFromServer()
            val antigos = getCachedBoletos()
            val alterado = novos.size != antigos.size ||
                    novos.zip(antigos).any { (novo, antigo) ->
                        novo.vencimento != antigo.vencimento ||
                                novo.status != antigo.status ||
                                novo.dataPagamento != antigo.dataPagamento
                    }
            if (alterado) saveBoletosCache(novos)
            alterado
        } catch (e: SessionExpiredException) {
            throw e
        } catch (e: Exception) {
            Log.e("Dados", "Erro em atualizaBoletos", e)
            false
        }
    }

    /**
     * Gera e baixa o boleto para um título em aberto.
     * Faz o POST equivalente a marcar o checkbox e clicar em "Gerar Boletos" no site.
     * @param tituloId Valor do campo "titulos" do formulário (ex: "0325841")
     * @return Uri do arquivo PDF salvo no cache, ou null em caso de erro.
     */
    suspend fun baixaBoleto(tituloId: String, vencimento: String): android.net.Uri? = withContext(Dispatchers.IO) {
        try {
            val partes = vencimento.split("/")
            val nomeArquivo = if (partes.size == 3) {
                "${partes[2]}_${partes[1]}.pdf"
            } else {
                "$tituloId.pdf"
            }

            // 1. Carrega a página para obter um CSRF token fresco
            val pageDoc = fetchPage(URL_BOLETOS)
            val csrfToken = pageDoc
                .selectFirst("#form-gerar-boletos input[name=__RequestVerificationToken]")
                ?.`val`()
                ?: run {
                    Log.e("Dados", "CSRF token não encontrado na página de boletos")
                    return@withContext null
                }

            val cookieStr = CookieManager.getInstance().getCookie(URL_BOLETOS) ?: ""

            val postData = buildString {
                append("__RequestVerificationToken=")
                append(URLEncoder.encode(csrfToken, "UTF-8"))
                append("&respFinanceiro=0")
                append("&titulos=")
                append(URLEncoder.encode(tituloId, "UTF-8"))
            }.toByteArray(Charsets.UTF_8)

            // 2. Faz o POST para gerar o boleto
            var conn = URL(URL_GERAR_BOLETO).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.doInput = true
            conn.connectTimeout = 30_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("Content-Length", postData.size.toString())
            conn.setRequestProperty("Cookie", cookieStr)
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Referer", URL_BOLETOS)
            conn.setRequestProperty("Accept", "application/pdf,text/html,*/*")
            conn.outputStream.use { it.write(postData) }

            var responseCode = conn.responseCode

            // 3. Segue redirecionamentos manualmente (POST pode redirecionar para GET)
            var redirectCount = 0
            while (responseCode in 301..302 && redirectCount < 5) {
                val location = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                val nextUrl = if (location.startsWith("http")) location
                else "https://interage.fei.org.br$location"
                val redirectConn = URL(nextUrl).openConnection() as HttpURLConnection
                redirectConn.instanceFollowRedirects = false
                redirectConn.requestMethod = "GET"
                redirectConn.connectTimeout = 30_000
                redirectConn.readTimeout = 30_000
                redirectConn.setRequestProperty("Cookie", cookieStr)
                redirectConn.setRequestProperty("User-Agent", USER_AGENT)
                responseCode = redirectConn.responseCode
                conn = redirectConn
                redirectCount++
            }

            Log.d("Dados", "BaixaBoleto: HTTP $responseCode, Content-Type=${conn.contentType}")

            // 4. Salva em Downloads/BoletosFEI/<nomeArquivo>
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
            val boletoDir = File(downloadsDir, "BoletosFEI").also { it.mkdirs() }
            val outputFile = File(boletoDir, nomeArquivo)

            conn.inputStream.use { input ->
                outputFile.outputStream().use { output -> input.copyTo(output) }
            }
            conn.disconnect()

            Log.d("Dados", "Boleto salvo: ${outputFile.absolutePath} (${outputFile.length()} bytes)")

            // 5. Notifica o MediaStore para o arquivo aparecer em gerenciadores de arquivos
            android.media.MediaScannerConnection.scanFile(
                appContext,
                arrayOf(outputFile.absolutePath),
                arrayOf("application/pdf"),
                null
            )

            // 6. Retorna Uri via FileProvider (usando external-path "downloads" do file_paths.xml)
            FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                outputFile
            )
        } catch (e: Exception) {
            Log.e("Dados", "Erro ao baixar boleto $tituloId", e)
            null
        }
    }

    // ===================== FUNÇÕES PRIVADAS DE REDE =====================

    @Throws(IOException::class)
    private suspend fun fetchPage(url: String): Document = withContext(Dispatchers.IO) {
        val cookies = CookieManager.getInstance().getCookie(url)
        try {
            val conn = Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .timeout(20000)
            if (!cookies.isNullOrBlank()) {
                conn.header("Cookie", cookies)
            }
            conn.get()
        } catch (e: IOException) {
            Log.e("Dados", "Erro de rede ao buscar $url", e)
            throw e
        }
    }

    private suspend fun fetchPerfilFromServer(): Perfil {
        val doc = fetchPage(URL_PERFIL)
        val panelBody = doc.selectFirst("body > div.container > div:nth-child(2) > div.col-md-9 > div.panel.panel-default.hidden-xs.bloco-conteudo-cabecalho > div.panel-body")
            ?: throw SessionExpiredException("Painel de perfil não encontrado")
        var nome = ""
        var matricula = ""
        var curso = ""
        panelBody.children().forEach { col ->
            val b = col.selectFirst("b")?.text()?.trim() ?: ""
            val em = col.selectFirst("small em")?.text()?.trim() ?: ""
            when {
                b.equals("Nome", ignoreCase = true) -> nome = em
                b.equals("Matrícula", ignoreCase = true) -> matricula = em
                b.equals("Curso", ignoreCase = true) -> curso = em
            }
        }
        val emailGroup = doc.selectFirst("#form-atualizar-dados-pessoais > div:nth-child(19)")
        val emailElement = emailGroup?.selectFirst("p.form-control-static")
        val email = emailElement?.text()?.trim() ?: ""
        Log.d("Dados", "Perfil carregado: $nome")
        return Perfil(nome, matricula, curso, email)
    }

    /**
     * Extrai boletos da tabela do formulário na página de tesouraria.
     * Seletor de referência: document.querySelector("#form-gerar-boletos")
     */
    private suspend fun fetchBoletosFromServer(): List<Boleto> {
        val doc = fetchPage(URL_BOLETOS)
        val form = doc.selectFirst("#form-gerar-boletos")
            ?: throw SessionExpiredException("Formulário de boletos não encontrado — sessão inválida")

        val tabela = form.selectFirst("table.table")
            ?: throw SessionExpiredException("Tabela de boletos não encontrada")

        val boletos = mutableListOf<Boleto>()
        val linhas = tabela.select("tbody > tr")

        for (linha in linhas) {
            val vencimento = linha.selectFirst("td[class*=Vencimento]")?.text()?.trim() ?: continue
            val status = linha.selectFirst("td[class*=Status]")?.text()?.trim() ?: continue
            val dataPagamento = linha.selectFirst("td[class*=Data]")?.text()?.trim() ?: ""
            val tituloId = linha.selectFirst("input[name=titulos]")?.`val`()?.trim() ?: ""

            if (vencimento.isNotEmpty() && status.isNotEmpty()) {
                boletos.add(Boleto(vencimento, status, dataPagamento, tituloId))
            }
        }

        Log.d("Dados", "Boletos carregados: ${boletos.size}")
        return boletos
    }

    // ===================== CACHE =====================

    private fun savePerfilCache(perfil: Perfil) {
        prefs.edit {
            putString(KEY_PERFIL, gson.toJson(perfil))
                .putLong(KEY_LAST_UPDATE_PERFIL, System.currentTimeMillis())
        }
    }

    private fun getCachedPerfil(): Perfil? {
        val json = prefs.getString(KEY_PERFIL, null) ?: return null
        return try {
            gson.fromJson(json, Perfil::class.java)
        } catch (_: Exception) { null }
    }

    private fun saveBoletosCache(boletos: List<Boleto>) {
        prefs.edit {
            putString(KEY_BOLETOS, gson.toJson(boletos))
                .putLong(KEY_LAST_UPDATE_BOLETOS, System.currentTimeMillis())
        }
    }

    private fun getCachedBoletos(): List<Boleto> {
        val json = prefs.getString(KEY_BOLETOS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<Boleto>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }
}