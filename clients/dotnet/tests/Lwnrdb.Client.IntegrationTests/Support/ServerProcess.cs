using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Threading.Tasks;

namespace Lwnrdb.Client.IntegrationTests.Support;

public sealed class ServerProcess : IAsyncDisposable
{
    public const string Admin = "admin";
    public const string Password = "Tst-Admin-9xQ2mV";
    private static readonly TimeSpan Startup = TimeSpan.FromSeconds(90);
    private static readonly TimeSpan Stop = TimeSpan.FromSeconds(30);

    private readonly Process _process;

    private ServerProcess(int port, string workDir, Process process)
    {
        Port = port;
        WorkDir = workDir;
        _process = process;
    }

    public int Port { get; }

    public string WorkDir { get; }

    public static async Task<ServerProcess> StartAsync(IReadOnlyDictionary<string, string>? overrides = null)
    {
        var jar = Path.GetFullPath(Environment.GetEnvironmentVariable("LWNRDB_JAR") ?? DefaultJar());
        if (!File.Exists(jar))
        {
            throw new FileNotFoundException($"no server jar at {jar}; run mvn package -DskipTests at the repository root");
        }
        var workDir = Directory.CreateTempSubdirectory("lwnrdb-it-").FullName;
        var port = FreePort();
        var config = new Dictionary<string, string>
        {
            ["port"] = port.ToString(System.Globalization.CultureInfo.InvariantCulture),
            ["filePath"] = "db",
            ["logPath"] = "logs",
            ["defaultAdminPassword"] = Password,
            ["maxMemory"] = "128Mb",
        };
        foreach (var (key, value) in overrides ?? new Dictionary<string, string>())
        {
            config[key] = value;
        }
        await File.WriteAllLinesAsync(Path.Combine(workDir, "lwnrdb.cfg"), config.Select(entry => $"{entry.Key}={entry.Value}"));
        var start = new ProcessStartInfo("java", ["-Xmx512m", "-XX:+UseG1GC", "-jar", jar])
        {
            WorkingDirectory = workDir,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
        };
        var process = Process.Start(start) ?? throw new InvalidOperationException("java did not start");
        var output = File.CreateText(Path.Combine(workDir, "server.out"));
        process.OutputDataReceived += (_, line) => Write(output, line.Data);
        process.ErrorDataReceived += (_, line) => Write(output, line.Data);
        process.BeginOutputReadLine();
        process.BeginErrorReadLine();
        var server = new ServerProcess(port, workDir, process);
        await server.AwaitPortAsync();
        return server;
    }

    public string Output()
    {
        using var stream = new FileStream(Path.Combine(WorkDir, "server.out"), FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
        using var reader = new StreamReader(stream);
        return reader.ReadToEnd();
    }

    public async ValueTask DisposeAsync()
    {
        if (!_process.HasExited)
        {
            _process.Kill(entireProcessTree: false);
            using var stop = new System.Threading.CancellationTokenSource(Stop);
            try
            {
                await _process.WaitForExitAsync(stop.Token);
            }
            catch (OperationCanceledException)
            {
                _process.Kill(entireProcessTree: true);
            }
        }
        _process.Dispose();
        try
        {
            Directory.Delete(WorkDir, recursive: true);
        }
        catch (IOException)
        {
            return;
        }
    }

    private static string DefaultJar() =>
        Path.Combine(AppContext.BaseDirectory, "..", "..", "..", "..", "..", "..", "..", "target", "lwnrdb.jar");

    private static void Write(StreamWriter output, string? line)
    {
        if (line is null)
        {
            return;
        }
        lock (output)
        {
            output.WriteLine(line);
            output.Flush();
        }
    }

    private static int FreePort()
    {
        using var probe = new TcpListener(IPAddress.Loopback, 0);
        probe.Start();
        return ((IPEndPoint)probe.LocalEndpoint).Port;
    }

    private async Task AwaitPortAsync()
    {
        var deadline = DateTime.UtcNow + Startup;
        while (DateTime.UtcNow < deadline)
        {
            if (_process.HasExited)
            {
                throw new InvalidOperationException($"the server exited: {Output()}");
            }
            try
            {
                using var probe = new TcpClient();
                await probe.ConnectAsync(IPAddress.Loopback, Port);
                return;
            }
            catch (SocketException)
            {
                await Task.Delay(200);
            }
        }
        _process.Kill(entireProcessTree: true);
        throw new TimeoutException($"the server did not open port {Port} within {Startup}");
    }
}
