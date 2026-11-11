using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Runtime.InteropServices;

namespace Lwnrdb.DocGen;

internal static class Program
{
    public static int Main(string[] args)
    {
        if (args.Length is < 3 or > 4)
        {
            Console.Error.WriteLine("usage: Lwnrdb.DocGen <assembly.dll> <apidocs folder> <output.xml> [--skeleton]");
            return 2;
        }
        var problems = Generate(args[0], args[1], args[2], args.Length == 4 && args[3] == "--skeleton");
        foreach (var problem in problems)
        {
            Console.Error.WriteLine(problem);
        }
        return problems.Count == 0 ? 0 : 1;
    }

    public static List<string> Generate(string assemblyPath, string docsFolder, string outputPath, bool writeSkeletons)
    {
        var assemblies = Directory.GetFiles(RuntimeEnvironment.GetRuntimeDirectory(), "*.dll")
            .Concat(Directory.GetFiles(Path.GetDirectoryName(Path.GetFullPath(assemblyPath))!, "*.dll"));
        using var context = new MetadataLoadContext(new PathAssemblyResolver(assemblies.DistinctBy(Path.GetFileName)));
        var assembly = context.LoadFromAssemblyPath(Path.GetFullPath(assemblyPath));
        var docs = ApiDocs.Read(docsFolder);
        var problems = new List<string>();
        var entries = new List<(string, DocSection)>();
        var seenFiles = new HashSet<string>(StringComparer.Ordinal);
        foreach (var type in PublicSurface.Types(assembly))
        {
            var file = Signatures.FileName(type);
            seenFiles.Add(file);
            var members = PublicSurface.Members(type).ToList();
            if (!docs.TryGetValue(file, out var doc))
            {
                problems.Add($"{file}: missing, so {DocIds.Of(type)} is undocumented");
                if (writeSkeletons)
                {
                    File.WriteAllText(Path.Combine(docsFolder, file), Skeleton(members));
                }
                continue;
            }
            entries.AddRange(Check(type, doc, members, problems));
        }
        problems.AddRange(docs.Keys.Where(file => !seenFiles.Contains(file)).Select(file => $"{file}: documents no public type"));
        Write(assembly.GetName().Name!, outputPath, entries);
        return problems;
    }

    private static IEnumerable<(string, DocSection)> Check(Type type, TypeDoc doc, List<MemberInfo> members, List<string> problems)
    {
        var file = Path.GetFileName(doc.File);
        if (doc.Summary.Text.Length == 0)
        {
            problems.Add($"{file}: the type has no summary");
        }
        yield return (DocIds.Of(type), doc.Summary);
        var bySignature = members.GroupBy(Signatures.Of).ToDictionary(group => group.Key, group => group.ToList(), StringComparer.Ordinal);
        foreach (var (signature, overloads) in bySignature)
        {
            if (!doc.Members.TryGetValue(signature, out var section))
            {
                problems.Add($"{file}: no section '## {signature}' for {DocIds.Of(overloads[0])}");
                continue;
            }
            foreach (var member in overloads)
            {
                yield return (DocIds.Of(member), section);
            }
        }
        problems.AddRange(doc.Members.Keys.Where(name => !bySignature.ContainsKey(name)).Select(name => $"{file}: section '## {name}' names no public member"));
    }

    private static void Write(string assemblyName, string outputPath, List<(string, DocSection)> entries)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(outputPath))!);
        XmlDocWriter.Document(assemblyName, entries).Save(outputPath);
    }

    private static string Skeleton(List<MemberInfo> members) =>
        "TODO\n" + string.Concat(members.Select(member => $"\n## {Signatures.Of(member)}\n\nTODO\n"));
}
