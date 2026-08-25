# antland

The CLI for [ants.land](https://ants.land), the package registry for the Ant
runtime. Works with npm, yarn, pnpm, and bun.

```sh
npx antland login        # authorize this device
npx antland add thing    # install a package
npx antland publish      # publish the current package
```

## Commands

| Command               | Description                               |
| --------------------- | ----------------------------------------- |
| `add`, `i`, `install` | Install one or more packages              |
| `remove`, `r`         | Remove one or more packages               |
| `publish`             | Publish the current package               |
| `npx`, `exec`, `x`    | Run a package binary after a safety check |
| `login`, `logout`     | Manage your publish token                 |
| `info`                | Show package information                  |
| `run <script>`        | Run a `package.json` script               |

Pass `--npm`, `--yarn`, `--pnpm`, or `--bun` to force a package manager, and
`-D` or `-O` to save to dev or optional dependencies. Set `ANTS_REGISTRY` to use
a different registry.

### Safe `npx`

`antland npx <pkg>` prints a safety report score, elevated-capability risks,
the publisher (and whether their GitHub is verified), and any typosquat warning.
then asks before running:

```sh
npx antland npx @acme/cli --help   # shows the report, prompts, then runs

  @acme/cli@1.2.0  ants.land

  Score      78/100
  Publisher  Jane Doe @jane  ✓ github:jane
  Risks      runs install scripts, network access

  Run this package? [y/N]
```

Pass `-y`/`--yes` to skip the prompt (required in non-interactive shells). The
report is built from `GET /api/package/score`. Anything after the package name is
forwarded verbatim to the program.

Full documentation: https://ants.land/docs/cli
