import { Icon } from './icons/Icon';
import { useTheme } from './motion/hooks';

/** Light/dark switch. Remembers the choice; otherwise follows the OS. */
export function ThemeToggle({ className = '' }: { className?: string }) {
  const [theme, toggle] = useTheme();
  const next = theme === 'dark' ? 'light' : 'dark';
  return (
    <button
      type="button"
      className={`theme-toggle${className ? ` ${className}` : ''}`}
      onClick={toggle}
      aria-label={`Switch to ${next} theme`}
      title={`Switch to ${next} theme`}
    >
      <Icon name={theme === 'dark' ? 'sun' : 'moon'} />
    </button>
  );
}
