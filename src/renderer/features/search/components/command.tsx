import { Command as Cmdk } from 'cmdk';

import './command.css';

export enum CommandPalettePages {
    GO_TO = 'go',
    HOME = 'home',
}

export const Command = Cmdk as typeof Cmdk;
